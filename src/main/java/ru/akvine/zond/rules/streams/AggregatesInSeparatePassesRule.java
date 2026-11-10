package ru.akvine.zond.rules.streams;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Несколько итогов по одной коллекции, каждый - своим проходом: минимум и максимум, количество и сумма.
 * {@code Collectors.teeing} собирает два итога за один проход, а у числовых стримов {@code summaryStatistics()}
 * дает сразу минимум, максимум, количество, сумму и среднее.
 */
@Component
public class AggregatesInSeparatePassesRule extends AbstractRule {
    private static final String STREAM = "stream";
    private static final String COLLECT = "collect";
    // Операции, которые меняют сам набор элементов: итоги по разным наборам одним проходом так просто не собрать
    private static final Set<String> SHAPING = Set.of(
            "filter", "distinct", "limit", "skip", "flatMap", "takeWhile", "dropWhile", "mapMulti");
    private static final String COLLECTIONS = "Collections";
    // Итоги, которые стрим считает сам
    private static final Set<String> TERMINALS = Set.of("min", "max", "count", "sum", "average");
    // Те же итоги коллектором: collect(Collectors.counting())
    private static final Map<String, String> COLLECTORS = Map.ofEntries(
            Map.entry("counting", "count"), Map.entry("minBy", "min"), Map.entry("maxBy", "max"),
            Map.entry("summingInt", "sum"), Map.entry("summingLong", "sum"), Map.entry("summingDouble", "sum"),
            Map.entry("averagingInt", "average"), Map.entry("averagingLong", "average"),
            Map.entry("averagingDouble", "average"));

    /**
     * Итог по коллекции
     *
     * @param kind    что считают: min, max, count, sum, average
     * @param source  по чему: orders
     * @param filters отбор перед подсчетом: filter, distinct, limit и подобные вместе с их аргументами
     */
    private record Aggregate(MethodCallExpr call, String kind, String source, String filters) {
    }

    @Override
    public String code() {
        return RuleCodes.AGGREGATES_IN_SEPARATE_PASSES_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет несколько итогов по одной коллекции отдельными проходами (min и max,"
                + " count и sum) вместо Collectors.teeing или summaryStatistics()";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // Итоги одного метода по одной коллекции с одним и тем же отбором
        Map<String, List<Aggregate>> groups = new LinkedHashMap<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            Optional<Node> callable = Nodes.enclosingCallable(call);
            if (callable.isEmpty()) {
                continue;
            }
            aggregate(call).ifPresent(found -> groups
                    .computeIfAbsent(System.identityHashCode(callable.get()) + ":" + found.source() + ":" + found.filters(),
                            key -> new ArrayList<>())
                    .add(found));
        }

        List<Violation> violations = new ArrayList<>();
        for (List<Aggregate> group : groups.values()) {
            Set<String> kinds = group.stream().map(Aggregate::kind).collect(Collectors.toCollection(LinkedHashSet::new));
            String firstKind = group.stream().map(Aggregate::kind).findFirst().orElse("");
            String list = String.join(", ", kinds);
            // Находка одна на коллекцию - на втором проходе: первый сам по себе в порядке
            group.stream()
                    .filter(found -> !found.kind().equals(firstKind))
                    .findFirst()
                    .ifPresent(second -> violations.add(violation(sourceFile, second.call(),
                            "По '" + second.source() + "' считают " + list + " отдельными проходами: коллекцию"
                                    + " обходят столько раз, сколько итогов; соберите их за один проход -"
                                    + " collect(Collectors.teeing(..., ..., (first, second) -> ...)), а у числовых"
                                    + " стримов (mapToInt, mapToLong, mapToDouble) - summaryStatistics(): в нем сразу"
                                    + " min, max, count, sum и average")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.INFO;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.STREAM;
    }

    private Optional<Aggregate> aggregate(MethodCallExpr call) {
        String name = call.getNameAsString();
        // Collections.min(orders), Collections.max(orders, comparator)
        if (MethodCalls.isCallOn(call, COLLECTIONS, name) && TERMINALS.contains(name) && !call.getArguments().isEmpty()) {
            return Optional.of(new Aggregate(call, name, Nodes.unwrap(call.getArgument(0)).toString(), ""));
        }
        Optional<String> kind = TERMINALS.contains(name) ? Optional.of(name) : collectorKind(call);
        if (kind.isEmpty() || call.getScope().isEmpty()) {
            return Optional.empty();
        }
        // orders.stream().filter(...).mapToInt(...).max(): идем по цепочке назад до stream()
        StringBuilder filters = new StringBuilder();
        Expression current = Nodes.unwrap(call.getScope().get());
        while (current.isMethodCallExpr()) {
            MethodCallExpr link = current.asMethodCallExpr();
            if (STREAM.equals(link.getNameAsString()) && link.getArguments().isEmpty() && link.getScope().isPresent()) {
                return Optional.of(new Aggregate(call, kind.get(), Nodes.unwrap(link.getScope().get()).toString(),
                        filters.toString()));
            }
            if (SHAPING.contains(link.getNameAsString())) {
                filters.append(link.getNameAsString()).append(link.getArguments());
            }
            if (link.getScope().isEmpty()) {
                return Optional.empty();
            }
            current = Nodes.unwrap(link.getScope().get());
        }
        return Optional.empty();
    }

    // collect(Collectors.counting()) -> count
    private Optional<String> collectorKind(MethodCallExpr call) {
        if (!COLLECT.equals(call.getNameAsString()) || call.getArguments().size() != 1) {
            return Optional.empty();
        }
        Expression collector = Nodes.unwrap(call.getArgument(0));
        return collector.isMethodCallExpr()
                ? Optional.ofNullable(COLLECTORS.get(collector.asMethodCallExpr().getNameAsString()))
                : Optional.empty();
    }
}
