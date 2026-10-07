package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.ForStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class OddCheckWithModuloRule extends AbstractRule {
    private static final String TWO = "2";
    private static final String ONE = "1";
    private static final String LENGTH = "length";
    // Результат этих вызовов отрицательным не бывает
    private static final Set<String> NON_NEGATIVE_CALLS = Set.of(
            "size", "length", "abs", "ordinal", "count", "indexOf", "getAndIncrement", "incrementAndGet",
            "getDayOfMonth", "getHour", "getMinute", "getSecond", "getYear", "getMonthValue", "nextInt", "floorMod",
            "availableProcessors", "getPageNumber", "getPageSize", "getRowNum", "getColumnIndex");

    @Override
    public String code() {
        return RuleCodes.ODD_CHECK_WITH_MODULO_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет проверку на нечетность через x % 2 == 1, которая неверна для отрицательных чисел";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (BinaryExpr comparison : sourceFile.unit().findAll(BinaryExpr.class)) {
            boolean compares = comparison.getOperator() == BinaryExpr.Operator.EQUALS
                    || comparison.getOperator() == BinaryExpr.Operator.NOT_EQUALS;
            if (!compares) {
                continue;
            }
            Expression left = Nodes.unwrap(comparison.getLeft());
            Expression right = Nodes.unwrap(comparison.getRight());
            Expression remainder = isOne(right) ? left : isOne(left) ? right : null;
            if (remainder == null || !isRemainderOfTwo(remainder)
                    || isNonNegative(remainder.asBinaryExpr().getLeft())) {
                continue;
            }
            Expression value = remainder.asBinaryExpr().getLeft();
            violations.add(violation(sourceFile, comparison,
                    "Нечетность проверяется как '" + comparison + "': для отрицательного числа остаток равен -1,"
                            + " и нечетное число будет принято за четное; сравнивайте с нулем ("
                            + value + " % 2 != 0) либо проверяйте младший бит ((" + value + " & 1) == 1)"));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private boolean isOne(Expression expression) {
        return expression.isIntegerLiteralExpr() && ONE.equals(expression.asIntegerLiteralExpr().getValue());
    }

    private boolean isRemainderOfTwo(Expression expression) {
        if (!expression.isBinaryExpr() || expression.asBinaryExpr().getOperator() != BinaryExpr.Operator.REMAINDER) {
            return false;
        }
        Expression divisor = Nodes.unwrap(expression.asBinaryExpr().getRight());
        return divisor.isIntegerLiteralExpr() && TWO.equals(divisor.asIntegerLiteralExpr().getValue());
    }

    // Размер, длина, модуль либо счетчик цикла, который идет от неотрицательного числа вверх
    private boolean isNonNegative(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (value.isMethodCallExpr()) {
            return NON_NEGATIVE_CALLS.contains(value.asMethodCallExpr().getNameAsString());
        }
        if (value.isFieldAccessExpr()) {
            return LENGTH.equals(value.asFieldAccessExpr().getNameAsString());
        }
        if (!value.isNameExpr()) {
            return false;
        }
        String name = value.asNameExpr().getNameAsString();
        return value.findAncestor(ForStmt.class, loop -> isUpwardCounter(loop, name)).isPresent();
    }

    // for (int i = 0; ...; i++)
    private boolean isUpwardCounter(ForStmt loop, String name) {
        boolean startsNonNegative = loop.getInitialization().stream()
                .filter(Expression::isVariableDeclarationExpr)
                .flatMap(declaration -> declaration.asVariableDeclarationExpr().getVariables().stream())
                .anyMatch(variable -> variable.getNameAsString().equals(name)
                        && variable.getInitializer().filter(Expression::isIntegerLiteralExpr).isPresent());
        boolean goesUp = loop.getUpdate().stream()
                .noneMatch(update -> update.toString().contains("--") || update.toString().contains("-="));
        return startsNonNegative && goesUp;
    }
}
