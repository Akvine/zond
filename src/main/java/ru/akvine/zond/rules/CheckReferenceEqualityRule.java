package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckReferenceEqualityRule extends AbstractRule {
    private static final String STRING = "String";
    private static final Set<String> BOXED_TYPES =
            Set.of("Integer", "Long", "Short", "Byte", "Character", "Double", "Float", "Boolean");

    @Override
    public String code() {
        return RuleCodes.CHECK_REFERENCE_EQUALITY_RULE_CODE;
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
            if (left.isNullLiteralExpr() || right.isNullLiteralExpr()) {
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
                    && rightType.filter(BOXED_TYPES::contains).isPresent()) {
                // Обертка против примитива распаковывается и сравнивается по значению, поэтому нужны обе обертки
                violations.add(violation(sourceFile, comparison,
                        "Сравнение оберток " + leftType.get() + " через " + operator + " в '" + comparison
                                + "': сравниваются ссылки, вне кэша значений результат будет false;"
                                + " используйте equals"));
            }
        }
        return violations;
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
