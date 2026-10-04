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
import ru.akvine.zond.rules.support.Nodes;

import java.util.List;
import java.util.Set;

@Component
public class CheckFindFirstIsPresentRule extends AbstractRule {
    private static final String FILTER = "filter";
    private static final Set<String> PRESENCE_CHECKS = Set.of("isPresent", "isEmpty");
    private static final Set<String> FIND_METHODS = Set.of("findFirst", "findAny");

    @Override
    public String code() {
        return RuleCodes.CHECK_FIND_FIRST_IS_PRESENT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет проверку существования элемента через filter().findFirst().isPresent()";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> PRESENCE_CHECKS.contains(call.getNameAsString()) && call.getArguments().isEmpty())
                .filter(call -> call.getScope().filter(this::isFilteredFind).isPresent())
                .map(call -> violation(sourceFile, call,
                        "Проверка существования через filter(...)." + findMethod(call) + "()." + call.getNameAsString()
                                + "(): то же самое короче и понятнее записывается как anyMatch(...)"
                                + " или noneMatch(...)"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.STREAM;
    }

    // filter(predicate).findFirst() - именно подряд: между ними нет map и других преобразований
    private boolean isFilteredFind(Expression scope) {
        Expression value = Nodes.unwrap(scope);
        return value.isMethodCallExpr()
                && FIND_METHODS.contains(value.asMethodCallExpr().getNameAsString())
                && value.asMethodCallExpr().getScope()
                .map(Nodes::unwrap)
                .filter(previous -> previous.isMethodCallExpr()
                        && FILTER.equals(previous.asMethodCallExpr().getNameAsString()))
                .isPresent();
    }

    private String findMethod(MethodCallExpr presenceCheck) {
        return presenceCheck.getScope()
                .map(Nodes::unwrap)
                .map(scope -> scope.asMethodCallExpr().getNameAsString())
                .orElse("");
    }
}
