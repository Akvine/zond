package ru.akvine.zond.rules.streams;

import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class CheckPeekWithSideEffectRule extends AbstractRule {
    private static final String PEEK = "peek";

    // Типы не разрешаем, поэтому изменение состояния узнаем по имени метода
    private static final Set<String> MUTATING_METHODS = Set.of(
            "add", "addAll", "put", "putAll", "putIfAbsent", "remove", "removeAll", "clear", "push", "offer",
            "append", "merge", "compute", "computeIfAbsent", "set", "increment", "incrementAndGet",
            "getAndIncrement", "decrementAndGet", "getAndDecrement", "addAndGet", "getAndAdd");

    // setName, setActive
    private static final Pattern SETTER = Pattern.compile("^set[A-Z].*");

    @Override
    public String code() {
        return RuleCodes.CHECK_PEEK_WITH_SIDE_EFFECT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет peek(), который изменяет состояние, а не просто наблюдает за элементами";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // peek() без аргументов - это не стрим, а очередь или итератор
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> PEEK.equals(call.getNameAsString()) && call.getArguments().size() == 1)
                .filter(call -> changesState(call.getArgument(0)))
                .map(call -> violation(sourceFile, call,
                        "'peek(" + call.getArgument(0) + ")' изменяет состояние: peek предназначен для отладки,"
                                + " может быть пропущен оптимизацией стрима и вызывается в непредсказуемом порядке;"
                                + " используйте map() или forEach()"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.STREAM;
    }

    private boolean changesState(Expression action) {
        // seen::add, user::setActive
        if (action.isMethodReferenceExpr()) {
            return isMutatingMethod(action.asMethodReferenceExpr().getIdentifier());
        }

        boolean assigns = !action.findAll(AssignExpr.class).isEmpty()
                || action.findAll(UnaryExpr.class).stream()
                .anyMatch(unary -> unary.getOperator().name().endsWith("INCREMENT")
                        || unary.getOperator().name().endsWith("DECREMENT"));
        return assigns || action.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> call.getScope().isPresent() && isMutatingMethod(call.getNameAsString()));
    }

    private boolean isMutatingMethod(String name) {
        return MUTATING_METHODS.contains(name) || SETTER.matcher(name).matches();
    }
}
