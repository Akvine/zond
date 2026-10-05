package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Guards;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class DivisionBySizeRule extends AbstractRule {
    private static final Set<String> SIZE_METHODS = Set.of("size", "length", "count");
    private static final Set<String> SIZE_CHECKS = Set.of("isEmpty", "size", "length", "count", "isNotEmpty");
    private static final String ZERO = "0";

    @Override
    public String code() {
        return RuleCodes.DIVISION_BY_SIZE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет деление на размер коллекции без проверки на пустоту";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(BinaryExpr.class).stream()
                .filter(division -> division.getOperator() == BinaryExpr.Operator.DIVIDE
                        || division.getOperator() == BinaryExpr.Operator.REMAINDER)
                .filter(division -> !TestClasses.isInside(division))
                .filter(this::dividesByUncheckedSize)
                .map(division -> violation(sourceFile, division,
                        "'" + division + "': делитель - размер коллекции, а на пустоту она не проверена: для целых"
                                + " будет ArithmeticException, для дробных - NaN; обработайте пустую коллекцию отдельно"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private boolean dividesByUncheckedSize(BinaryExpr division) {
        Expression divisor = Nodes.unwrap(division.getRight());
        // total / items.size() либо int count = items.size(); total / count
        Optional<String> collection = sizedCollection(divisor)
                .or(() -> LocalTypes.findInitializer(divisor).map(Nodes::unwrap).flatMap(this::sizedCollection));
        if (collection.isEmpty()) {
            return false;
        }
        String variable = divisor.toString();
        return !Guards.isGuarded(division, check -> isSizeCheck(check, collection.get())
                || isComparedWithZero(check, variable));
    }

    /**
     * @return коллекция, размер которой берет выражение: items для items.size()
     */
    private Optional<String> sizedCollection(Expression expression) {
        if (!expression.isMethodCallExpr()) {
            return Optional.empty();
        }
        return Optional.of(expression.asMethodCallExpr())
                .filter(call -> SIZE_METHODS.contains(call.getNameAsString()) && call.getArguments().isEmpty())
                .flatMap(call -> call.getScope())
                .map(Expression::toString);
    }

    private boolean isSizeCheck(Expression check, String collection) {
        return check.isMethodCallExpr()
                && SIZE_CHECKS.contains(check.asMethodCallExpr().getNameAsString())
                && (check.asMethodCallExpr().getScope().filter(scope -> scope.toString().equals(collection)).isPresent()
                || check.asMethodCallExpr().getArguments().stream()
                .anyMatch(argument -> argument.toString().equals(collection)));
    }

    // count == 0, count > 0
    private boolean isComparedWithZero(Expression check, String variable) {
        if (!check.isBinaryExpr()) {
            return false;
        }
        String left = check.asBinaryExpr().getLeft().toString();
        String right = check.asBinaryExpr().getRight().toString();
        return left.equals(variable) && right.equals(ZERO) || left.equals(ZERO) && right.equals(variable);
    }
}
