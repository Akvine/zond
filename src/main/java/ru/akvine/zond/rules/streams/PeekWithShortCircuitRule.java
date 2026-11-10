package ru.akvine.zond.rules.streams;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Loggers;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.StreamChains;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * peek() перед операцией, которая не дочитывает стрим. Стрим ленив: элемент идет по конвейеру, только пока
 * его ждет завершающая операция. findFirst(), anyMatch() или limit() останавливают его раньше конца - и действие
 * из peek() выполняется не для всех элементов, а count() может не запустить конвейер вовсе.
 */
@Component
public class PeekWithShortCircuitRule extends AbstractRule {
    private static final String PEEK = "peek";
    private static final String COUNT = "count";
    // Останавливают стрим, не дочитав его
    private static final Set<String> SHORT_CIRCUIT = Set.of(
            "findFirst", "findAny", "anyMatch", "allMatch", "noneMatch", "limit", "takeWhile");
    // Сортировка сначала собирает все элементы: до нее peek успеет увидеть каждый
    private static final Set<String> BARRIERS = Set.of("sorted");
    // Операции, из-за которых число элементов заранее неизвестно: с ними count() честно проходит весь стрим
    private static final Set<String> SIZE_CHANGING = Set.of(
            "filter", "flatMap", "flatMapToInt", "flatMapToLong", "flatMapToDouble", "mapMulti", "distinct",
            "takeWhile", "dropWhile", "limit", "skip");
    // Источники, размер которых стриму неизвестен
    private static final Set<String> UNSIZED_SOURCES = Set.of("lines", "iterate", "generate", "walk", "list", "find");
    private static final String SYSTEM_OUT = "System.out";
    private static final String SYSTEM_ERR = "System.err";
    private static final Set<String> LOGGER_NAMES = Set.of("log", "logger", "LOG", "LOGGER");

    @Override
    public String code() {
        return RuleCodes.PEEK_WITH_SHORT_CIRCUIT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет peek() перед findFirst(), anyMatch(), limit() и count(): действие из peek"
                + " выполнится не для всех элементов";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr peek : sourceFile.unit().findAll(MethodCallExpr.class)) {
            // peek() без аргументов - это не стрим, а очередь или итератор
            if (!PEEK.equals(peek.getNameAsString()) || peek.getArguments().size() != 1) {
                continue;
            }
            // Цепочка может занимать несколько строк: находка ставится на строку самого peek
            describe(peek).ifPresent(problem -> violations.add(violation(sourceFile, peek.getName(),
                    "'peek(" + Nodes.text(peek.getArgument(0)) + ")' " + problem
                            + "; если действие нужно для каждого элемента, выполните его отдельным проходом"
                            + " (forEach) до поиска либо перенесите в завершающую операцию")
                    .withLevel(isOnlyLogging(peek.getArgument(0)) ? ErrorLevel.INFO : ErrorLevel.MINOR)));
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

    private Optional<String> describe(MethodCallExpr peek) {
        List<MethodCallExpr> after = StreamChains.callsAfter(peek);
        for (MethodCallExpr operation : after) {
            String name = operation.getNameAsString();
            if (BARRIERS.contains(name)) {
                return Optional.empty();
            }
            if (SHORT_CIRCUIT.contains(name)) {
                return Optional.of("стоит перед " + name + "(): стрим ленив и останавливается, как только " + name
                        + "() получил свой ответ, - остальные элементы до peek не дойдут, и действие выполнится"
                        + " только для части из них");
            }
        }

        boolean counted = !after.isEmpty() && COUNT.equals(after.get(after.size() - 1).getNameAsString())
                && after.get(after.size() - 1).getArguments().isEmpty();
        List<MethodCallExpr> chain = new ArrayList<>(after);
        chain.addAll(StreamChains.callsBefore(peek));
        boolean sized = chain.stream().map(MethodCallExpr::getNameAsString)
                .noneMatch(name -> SIZE_CHANGING.contains(name) || UNSIZED_SOURCES.contains(name));
        return counted && sized
                ? Optional.of("стоит перед count(): число элементов известно по источнику, поэтому с Java 9 count()"
                + " возвращает его, не запуская конвейер, - действие из peek может не выполниться ни разу")
                : Optional.empty();
    }

    // peek(System.out::println), peek(item -> log.debug("item {}", item)): отладочный вывод, а не работа с данными
    private boolean isOnlyLogging(Expression action) {
        if (action.isMethodReferenceExpr()) {
            String scope = action.asMethodReferenceExpr().getScope().toString();
            return SYSTEM_OUT.equals(scope) || SYSTEM_ERR.equals(scope) || LOGGER_NAMES.contains(scope);
        }
        if (!action.isLambdaExpr()) {
            return false;
        }
        List<MethodCallExpr> calls = action.asLambdaExpr().getBody().findAll(MethodCallExpr.class).stream()
                .filter(call -> !isArgumentOfLog(call))
                .toList();
        return !calls.isEmpty() && calls.stream().allMatch(this::isLog);
    }

    private boolean isLog(MethodCallExpr call) {
        String scope = call.getScope().map(Expression::toString).orElse("");
        return Loggers.isLogCall(call) || SYSTEM_OUT.equals(scope) || SYSTEM_ERR.equals(scope);
    }

    // log.debug("order {}", order.getId()): чтение свойства ради вывода действием не считается
    private boolean isArgumentOfLog(MethodCallExpr call) {
        Optional<MethodCallExpr> outer = call.findAncestor(MethodCallExpr.class);
        while (outer.isPresent()) {
            if (isLog(outer.get())) {
                return true;
            }
            outer = outer.get().findAncestor(MethodCallExpr.class);
        }
        return false;
    }
}
