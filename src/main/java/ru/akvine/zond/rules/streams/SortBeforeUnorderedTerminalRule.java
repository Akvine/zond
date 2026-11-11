package ru.akvine.zond.rules.streams;

import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.StreamChains;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * items.stream().sorted().collect(Collectors.toSet()), sorted().count(), sorted().anyMatch(...): стрим
 * сортируют, а итог от порядка не зависит - сортировка O(N log N) выполняется зря.
 */
@Component
public class SortBeforeUnorderedTerminalRule extends AbstractRule {
    private static final String SORTED = "sorted";
    private static final String COLLECT = "collect";
    // Операции, которые порядок не используют и не показывают
    private static final Set<String> PASSING = Set.of(
            "filter", "map", "mapToInt", "mapToLong", "mapToDouble", "mapToObj", "boxed", "distinct", "flatMap",
            "unordered", "parallel", "sequential", "asLongStream", "asDoubleStream");
    private static final Set<String> UNORDERED_RESULTS = Set.of(
            "count", "anyMatch", "allMatch", "noneMatch", "sum", "average", "min", "max", "summaryStatistics");
    // collect(Collectors.toSet()), collect(toUnmodifiableSet()): у множества порядка нет
    private static final Pattern SET_COLLECTOR = Pattern.compile("^(Collectors\\.)?to(Unmodifiable)?Set\\(\\)$");

    @Override
    public String code() {
        return RuleCodes.SORT_BEFORE_UNORDERED_TERMINAL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет sorted() в стриме, итог которого от порядка не зависит: count, anyMatch, toSet";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr sorted : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!SORTED.equals(sorted.getNameAsString()) || sorted.getArguments().size() > 1
                    || sorted.getScope().isEmpty() || TestClasses.isInside(sorted)) {
                continue;
            }
            for (MethodCallExpr next : StreamChains.callsAfter(sorted)) {
                if (PASSING.contains(next.getNameAsString())) {
                    continue;
                }
                if (ignoresOrder(next)) {
                    violations.add(violation(sourceFile, sorted.getName(),
                            "sorted() перед " + next.getNameAsString() + "(...): итог от порядка элементов не"
                                    + " зависит, и сортировка O(N log N) выполняется зря; уберите sorted()"));
                }
                // limit, findFirst, toList, forEach и все незнакомое: порядок может быть нужен
                break;
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.STREAM;
    }

    private boolean ignoresOrder(MethodCallExpr terminal) {
        if (UNORDERED_RESULTS.contains(terminal.getNameAsString())) {
            return true;
        }
        return COLLECT.equals(terminal.getNameAsString()) && terminal.getArguments().size() == 1
                && SET_COLLECTOR.matcher(terminal.getArgument(0).toString()).matches();
    }
}
