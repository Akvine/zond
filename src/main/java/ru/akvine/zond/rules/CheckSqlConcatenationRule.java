package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class CheckSqlConcatenationRule extends AbstractRule {
    private static final String PLACEHOLDER = "?";

    // Строка должна именно начинаться как запрос, иначе под правило попадут сообщения логов со словами select / from
    private static final Pattern SQL = Pattern.compile(
            "^\\s*(select\\b.+\\bfrom\\b|insert\\s+into\\b|update\\s+\\S+\\s+set\\b|delete\\s+from\\b)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // Имя таблицы или схемы из константы - не пользовательский ввод
    private static final Pattern CONSTANT_NAME = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    @Override
    public String code() {
        return RuleCodes.CHECK_SQL_CONCATENATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет SQL-запросы, собранные конкатенацией строк с параметрами";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (BinaryExpr concatenation : sourceFile.unit().findAll(BinaryExpr.class)) {
            if (concatenation.getOperator() != BinaryExpr.Operator.PLUS || isPartOfConcatenation(concatenation)) {
                continue;
            }

            List<Expression> operands = new ArrayList<>();
            flatten(concatenation, operands);

            StringBuilder text = new StringBuilder();
            List<String> dynamicParts = new ArrayList<>();
            for (Expression operand : operands) {
                if (operand.isStringLiteralExpr()) {
                    text.append(operand.asStringLiteralExpr().asString());
                } else if (operand.isTextBlockLiteralExpr()) {
                    text.append(operand.asTextBlockLiteralExpr().asString());
                } else {
                    text.append(PLACEHOLDER);
                    if (isDynamic(operand)) {
                        dynamicParts.add(operand.toString());
                    }
                }
            }

            if (!dynamicParts.isEmpty() && SQL.matcher(text).find()) {
                violations.add(violation(sourceFile, concatenation,
                        "SQL-запрос собирается конкатенацией с " + String.join(", ", dynamicParts)
                                + ": возможна SQL-инъекция; передавайте значения параметрами запроса"));
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
        return ErrorType.SECURITY;
    }

    // "a" + b + "c" разбирается как ("a" + b) + "c": проверяем только самое внешнее выражение
    private boolean isPartOfConcatenation(BinaryExpr expression) {
        Node parent = expression.getParentNode().orElse(null);
        while (parent instanceof EnclosedExpr) {
            parent = parent.getParentNode().orElse(null);
        }
        return parent instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS;
    }

    private void flatten(Expression expression, List<Expression> operands) {
        Expression value = Nodes.unwrap(expression);
        if (value.isBinaryExpr() && value.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
            flatten(value.asBinaryExpr().getLeft(), operands);
            flatten(value.asBinaryExpr().getRight(), operands);
        } else {
            operands.add(value);
        }
    }

    private boolean isDynamic(Expression operand) {
        if (operand.isLiteralExpr()) {
            return false;
        }
        if (operand.isNameExpr()) {
            return !CONSTANT_NAME.matcher(operand.asNameExpr().getNameAsString()).matches();
        }
        if (operand.isFieldAccessExpr()) {
            return !CONSTANT_NAME.matcher(operand.asFieldAccessExpr().getNameAsString()).matches();
        }
        return true;
    }
}
