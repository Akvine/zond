package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Loggers;
import ru.akvine.zond.rules.support.Nodes;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Дорогой аргумент сообщения debug / trace: сериализация в JSON, обход коллекции стримом. Аргумент вычисляется
 * до вызова логгера, даже когда этот уровень выключен, - а в рабочей среде он выключен почти всегда.
 * О конкатенации и String.format сообщает jr:93.
 */
@Component
public class ExpensiveLogArgumentRule extends AbstractRule {
    // Уровни, которые в рабочей среде обычно выключены
    private static final Set<String> QUIET_LEVELS = Set.of("debug", "trace");
    private static final Set<String> LEVEL_CHECKS = Set.of("isDebugEnabled", "isTraceEnabled", "isEnabledForLevel");

    // Сериализация и отражение: Jackson, Gson, fastjson, JAXB, commons-lang
    private static final Set<String> EXPENSIVE_CALLS = Set.of(
            "writeValueAsString", "writeValueAsBytes", "valueToTree", "toPrettyString", "toJson", "toJSONString",
            "toJsonString", "reflectionToString", "marshal", "deepToString");
    private static final String STREAM = "stream";
    private static final Set<String> STREAM_TERMINALS = Set.of("collect", "toList", "joining", "reduce", "count");

    @Override
    public String code() {
        return RuleCodes.EXPENSIVE_LOG_ARGUMENT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет дорогие аргументы debug / trace (сериализация в JSON, обход стримом),"
                + " которые вычисляются, даже когда этот уровень логирования выключен";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(Loggers::isLogCall)
                .filter(call -> QUIET_LEVELS.contains(call.getNameAsString()))
                .filter(call -> !isGuarded(call))
                .flatMap(call -> call.getArguments().stream()
                        .map(this::expensivePart)
                        .flatMap(Optional::stream)
                        .findFirst()
                        .map(expensive -> violation(sourceFile, call,
                                "Аргумент '" + expensive + "' для " + call.getScope().get() + "." + call.getNameAsString()
                                        + "(...) вычисляется всегда, даже когда уровень " + call.getNameAsString()
                                        + " выключен, - а в рабочей среде он выключен почти всегда; оберните вызов"
                                        + " в if (log.is" + capitalize(call.getNameAsString()) + "Enabled()) либо"
                                        + " передайте ленивый аргумент: log.at" + capitalize(call.getNameAsString())
                                        + "().addArgument(() -> ...)"))
                        .stream())
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    // Лямбда и ссылка на метод вычисляются лениво - только если уровень включен
    private Optional<String> expensivePart(Expression argument) {
        Expression value = Nodes.unwrap(argument);
        if (value instanceof LambdaExpr || value instanceof MethodReferenceExpr) {
            return Optional.empty();
        }
        List<MethodCallExpr> calls = value.findAll(MethodCallExpr.class).stream()
                .filter(call -> call == value || !Nodes.isInNestedScope(call, value))
                .toList();
        Optional<MethodCallExpr> serialization = calls.stream()
                .filter(call -> EXPENSIVE_CALLS.contains(call.getNameAsString()))
                .findFirst();
        if (serialization.isPresent()) {
            return Optional.of(serialization.get().toString());
        }
        boolean streams = calls.stream().anyMatch(call -> STREAM.equals(call.getNameAsString()))
                && calls.stream().anyMatch(call -> STREAM_TERMINALS.contains(call.getNameAsString()));
        return streams ? Optional.of(value.toString()) : Optional.empty();
    }

    // if (log.isDebugEnabled()) { log.debug(...) }
    private boolean isGuarded(MethodCallExpr call) {
        Node current = call.getParentNode().orElse(null);
        while (current != null && !(current instanceof BodyDeclaration<?>)) {
            if (current instanceof IfStmt branch && branch.getCondition().findAll(MethodCallExpr.class).stream()
                    .anyMatch(check -> LEVEL_CHECKS.contains(check.getNameAsString()))) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    private String capitalize(String level) {
        return Character.toUpperCase(level.charAt(0)) + level.substring(1);
    }
}
