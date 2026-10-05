package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractTaintRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Taint;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class CheckSqlConcatenationRule extends AbstractTaintRule {
    private static final String PLACEHOLDER = "?";

    // Строка должна именно начинаться как запрос, иначе под правило попадут сообщения логов со словами select / from
    private static final Pattern SQL = Pattern.compile(
            "^\\s*(select\\b.+\\bfrom\\b|insert\\s+into\\b|update\\s+\\S+\\s+set\\b|delete\\s+from\\b)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Override
    public String code() {
        return RuleCodes.CHECK_SQL_CONCATENATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет SQL-запросы, в текст которых конкатенацией попадают данные извне";
    }

    @Override
    protected List<Violation> check(SourceFile sourceFile, Taint taint) {
        List<Violation> violations = new ArrayList<>();
        for (BinaryExpr concatenation : sourceFile.unit().findAll(BinaryExpr.class)) {
            if (concatenation.getOperator() != BinaryExpr.Operator.PLUS || isPartOfConcatenation(concatenation)) {
                continue;
            }
            List<Expression> operands = new ArrayList<>();
            flatten(concatenation, operands);

            StringBuilder text = new StringBuilder();
            List<String> clientData = new ArrayList<>();
            for (Expression operand : operands) {
                if (operand.isStringLiteralExpr()) {
                    text.append(operand.asStringLiteralExpr().asString());
                } else if (operand.isTextBlockLiteralExpr()) {
                    text.append(operand.asTextBlockLiteralExpr().asString());
                } else {
                    text.append(PLACEHOLDER);
                    // Инъекция возможна, только если значением управляет клиент. Имя таблицы из константы,
                    // значение из настроек или посчитанное в коде нарушителю недоступно
                    Optional<Taint.Source> source = operand.isLiteralExpr() ? Optional.empty() : taint.findSource(operand);
                    source.ifPresent(found -> clientData.add(operand + " (" + found.describe() + ")"));
                }
            }
            if (!clientData.isEmpty() && SQL.matcher(text).find()) {
                violations.add(violation(sourceFile, concatenation,
                        "В SQL-запрос конкатенацией попадает " + String.join(", ", clientData)
                                + ": тот, кто управляет этим значением, может дописать к запросу свой SQL; передавайте значения"
                                + " параметрами запроса"));
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
}
