package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckSizeComparedToZeroRule extends AbstractRule {
    private static final String SIZE = "size";
    private static final String LENGTH = "length";
    private static final String STRING = "String";
    private static final String IS_EMPTY = "isEmpty";
    private static final String ZERO = "0";
    private static final String ONE = "1";

    private static final Set<BinaryExpr.Operator> EQUALITY =
            Set.of(BinaryExpr.Operator.EQUALS, BinaryExpr.Operator.NOT_EQUALS);

    @Override
    public String code() {
        return RuleCodes.CHECK_SIZE_COMPARED_TO_ZERO_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет size() == 0 и length() == 0 вместо isEmpty()";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(BinaryExpr.class).stream()
                .filter(comparison -> !isInsideIsEmpty(comparison))
                .flatMap(comparison -> findEmptinessCheck(comparison)
                        .map(call -> violation(sourceFile, comparison,
                                "'" + comparison + "': пустоту проверяет isEmpty() - он говорит о намерении прямо,"
                                        + " а для некоторых коллекций еще и не пересчитывает элементы; замените"
                                        + " на " + call.getScope().get() + ".isEmpty()"))
                        .stream())
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    /**
     * @return вызов size() / length(), который сравнение проверяет на пустоту
     */
    private Optional<MethodCallExpr> findEmptinessCheck(BinaryExpr comparison) {
        Expression left = Nodes.unwrap(comparison.getLeft());
        Expression right = Nodes.unwrap(comparison.getRight());
        BinaryExpr.Operator operator = comparison.getOperator();

        // size() == 0, size() != 0, size() > 0, size() < 1, size() >= 1 и то же в зеркальной записи
        boolean leftForm = EQUALITY.contains(operator) && isLiteral(right, ZERO)
                || operator == BinaryExpr.Operator.GREATER && isLiteral(right, ZERO)
                || operator == BinaryExpr.Operator.LESS && isLiteral(right, ONE)
                || operator == BinaryExpr.Operator.GREATER_EQUALS && isLiteral(right, ONE);
        boolean rightForm = EQUALITY.contains(operator) && isLiteral(left, ZERO)
                || operator == BinaryExpr.Operator.LESS && isLiteral(left, ZERO);
        if (leftForm) {
            return sizeCall(left);
        }
        return rightForm ? sizeCall(right) : Optional.empty();
    }

    private Optional<MethodCallExpr> sizeCall(Expression expression) {
        if (!expression.isMethodCallExpr()) {
            return Optional.empty();
        }
        MethodCallExpr call = expression.asMethodCallExpr();
        if (call.getScope().isEmpty() || !call.getArguments().isEmpty()) {
            return Optional.empty();
        }
        // length() есть и у StringBuilder, и у File - isEmpty() заменяет его только у строки
        boolean isSize = SIZE.equals(call.getNameAsString());
        boolean isStringLength = LENGTH.equals(call.getNameAsString())
                && LocalTypes.typeOf(call.getScope().get()).filter(STRING::equals).isPresent();
        return isSize || isStringLength ? Optional.of(call) : Optional.empty();
    }

    private boolean isLiteral(Expression expression, String value) {
        return expression.isIntegerLiteralExpr() && value.equals(expression.asIntegerLiteralExpr().getValue());
    }

    // Собственный isEmpty() коллекции как раз и пишется через size() == 0
    private boolean isInsideIsEmpty(BinaryExpr comparison) {
        return Nodes.enclosingCallable(comparison)
                .filter(callable -> callable instanceof MethodDeclaration method
                        && IS_EMPTY.equals(method.getNameAsString()))
                .isPresent();
    }
}
