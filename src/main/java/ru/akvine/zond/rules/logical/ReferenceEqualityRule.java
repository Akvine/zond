package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.AssertStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class ReferenceEqualityRule extends AbstractRule {
    private static final String STRING = "String";
    // Boolean сюда не входит: упаковка всегда дает Boolean.TRUE либо Boolean.FALSE, и ссылки совпадают
    private static final Set<String> BOXED_TYPES = Set.of("Integer", "Long", "Short", "Byte", "Character", "Double", "Float");
    private static final String INTERN = "intern";
    private static final Set<BinaryExpr.Operator> ARITHMETIC = Set.of(
            BinaryExpr.Operator.PLUS, BinaryExpr.Operator.MINUS, BinaryExpr.Operator.MULTIPLY, BinaryExpr.Operator.DIVIDE,
            BinaryExpr.Operator.REMAINDER, BinaryExpr.Operator.BINARY_AND, BinaryExpr.Operator.BINARY_OR,
            BinaryExpr.Operator.XOR, BinaryExpr.Operator.LEFT_SHIFT, BinaryExpr.Operator.SIGNED_RIGHT_SHIFT,
            BinaryExpr.Operator.UNSIGNED_RIGHT_SHIFT);

    @Override
    public String code() {
        return RuleCodes.REFERENCE_EQUALITY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет сравнение строк и оберток (Integer, Long и т.п.) через == вместо equals";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (BinaryExpr comparison : sourceFile.unit().findAll(BinaryExpr.class)) {
            if (comparison.getOperator() != BinaryExpr.Operator.EQUALS
                    && comparison.getOperator() != BinaryExpr.Operator.NOT_EQUALS) {
                continue;
            }

            Expression left = Nodes.unwrap(comparison.getLeft());
            Expression right = Nodes.unwrap(comparison.getRight());
            // Сравнение ссылок задумано: assert v == TIMESTAMP_FIELD_NAME, name == name.intern(), key != normalized
            if (left.isNullLiteralExpr() || right.isNullLiteralExpr() || isIntendedIdentity(comparison, left, right)) {
                continue;
            }

            Optional<String> leftType = LocalTypes.typeOf(left);
            Optional<String> rightType = LocalTypes.typeOf(right);
            String operator = comparison.getOperator().asString();

            if (leftType.filter(STRING::equals).isPresent() || rightType.filter(STRING::equals).isPresent()) {
                violations.add(violation(sourceFile, comparison,
                        "Сравнение строк через " + operator + " в '" + comparison + "': сравниваются ссылки,"
                                + " а не содержимое; используйте equals"));
            } else if (leftType.filter(BOXED_TYPES::contains).isPresent()
                    && rightType.filter(BOXED_TYPES::contains).isPresent()
                    && !isPrimitiveValue(left) && !isPrimitiveValue(right)) {
                // Обертка против примитива распаковывается и сравнивается по значению, поэтому нужны обе обертки
                violations.add(violation(sourceFile, comparison,
                        "Сравнение оберток " + leftType.get() + " через " + operator + " в '" + comparison
                                + "': сравниваются ссылки, вне кэша значений результат будет false;"
                                + " используйте equals"));
            }
        }
        return violations;
    }

    private boolean isIntendedIdentity(BinaryExpr comparison, Expression left, Expression right) {
        return comparison.findAncestor(AssertStmt.class).isPresent()
                || comparison.findAll(MethodCallExpr.class).stream().anyMatch(call -> INTERN.equals(call.getNameAsString()))
                || isSameInstanceCheck(left, right) || isSameInstanceCheck(right, left);
    }

    /**
     * String normalizedKey = normalizeKeyPrefix(entry.getKey(), prefix); if (normalizedKey != entry.getKey()):
     * метод возвращает тот же объект, если менять нечего, и сравнение ссылок отвечает "изменилось ли"
     */
    private boolean isSameInstanceCheck(Expression result, Expression original) {
        return result.isNameExpr() && LocalTypes.findInitializer(result)
                .map(Nodes::unwrap)
                .filter(Expression::isMethodCallExpr)
                .filter(initializer -> initializer.asMethodCallExpr().getArguments().stream()
                        .anyMatch(argument -> argument.toString().equals(original.toString())))
                .isPresent();
    }

    // total == failed + successful: арифметика распаковывает обертки, и сравниваются числа
    private boolean isPrimitiveValue(Expression expression) {
        return expression.isLiteralExpr() || expression.isUnaryExpr()
                || expression.isBinaryExpr() && ARITHMETIC.contains(expression.asBinaryExpr().getOperator());
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }
}
