package ru.akvine.zond.rules.performance;

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

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class OptionalOrElseCallRule extends AbstractRule {
    private static final String OR_ELSE = "orElse";
    private static final String ELEMENT_READ = "get";
    // Имя начинается с глагола - метод что-то ищет, создает или считает
    private static final Pattern ACTION = Pattern.compile(
            "^(get|find|load|fetch|read|query|search|select|create|build|make|new|generate|compute|calculate|calc|resolve"
                    + "|parse|request|call|send|execute|run|count|sum|collect|convert|transform|copy|clone|sort|list"
                    + "|scan|open|connect|download|upload|save|delete|update|insert|next|random|now|current|default"
                    + "|empty|of|from|to|as|with)([A-Z_].*)?$");

    // getName(), isActive(), toString()
    private static final Pattern GETTER = Pattern.compile("^(get|is)[A-Z].*|^toString$");

    // Дешевые вызовы, которые ничего не вычисляют: Collections.emptyList(), List.of(), Optional.empty()
    private static final Set<String> CHEAP_SOURCES = Set.of(
            "Collections", "List", "Set", "Map", "Optional", "BigDecimal", "String", "Boolean", "Integer", "Long");

    @Override
    public String code() {
        return RuleCodes.OPTIONAL_OR_ELSE_CALL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет Optional.orElse() с вызовом метода в аргументе";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> OR_ELSE.equals(call.getNameAsString()) && call.getArguments().size() == 1)
                .filter(call -> call.getScope().isPresent() && isComputed(call.getArgument(0)))
                .map(call -> violation(sourceFile, call,
                        "orElse(" + call.getArgument(0) + "): аргумент вычисляется всегда, даже когда значение в"
                                + " Optional есть - лишний вызов, а при побочных эффектах еще и ошибка;"
                                + " используйте orElseGet(() -> ...)"))
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

    /**
     * Чтение готового значения: элемент по номеру или ключу (items.get(0), DEFAULTS.get(key)), свойство записи или
     * перечисления (stage.line(), flag.name()) и их цепочка. Метод с глаголом в имени (findDefault(), create())
     * и вызов своего метода без получателя остаются вычислением
     */
    private boolean isCheapRead(MethodCallExpr call) {
        Expression scope = call.getScope().map(Nodes::unwrap).orElse(null);
        if (scope == null) {
            return false;
        }
        boolean cheapScope = scope.isNameExpr() || scope.isFieldAccessExpr() || scope.isThisExpr()
                || scope.isMethodCallExpr() && isCheapRead(scope.asMethodCallExpr());
        boolean property = call.getArguments().isEmpty() && !ACTION.matcher(call.getNameAsString()).matches();
        return cheapScope && (property || isElementRead(call));
    }

    // items.get(0), DEFAULTS.get(key)
    private boolean isElementRead(MethodCallExpr call) {
        return ELEMENT_READ.equals(call.getNameAsString()) && call.getArguments().size() == 1
                && (call.getArgument(0).isLiteralExpr() || call.getArgument(0).isNameExpr());
    }

    // Вызов метода или создание объекта, кроме заведомо дешевых фабрик
    private boolean isComputed(Expression argument) {
        Expression value = Nodes.unwrap(argument);
        if (value.isObjectCreationExpr()) {
            return true;
        }
        if (!value.isMethodCallExpr()) {
            return false;
        }

        // Геттер без аргументов просто читает поле - вычислением его считать незачем
        MethodCallExpr call = value.asMethodCallExpr();
        if (call.getArguments().isEmpty() && GETTER.matcher(call.getNameAsString()).matches() || isCheapRead(call)) {
            return false;
        }
        return call.getScope()
                .filter(scope -> CHEAP_SOURCES.stream().anyMatch(type -> MethodCalls.isType(scope, type)))
                .isEmpty();
    }
}
