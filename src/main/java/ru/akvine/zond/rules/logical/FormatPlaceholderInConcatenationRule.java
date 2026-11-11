package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithArguments;
import com.github.javaparser.ast.stmt.Statement;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * new ReportException("Не удалось прочитать файл [%s]" + exception.getMessage()): значение приклеили к строке,
 * а подстановку %s никто не заполняет - она так и уйдет в текст ошибки.
 */
@Component
public class FormatPlaceholderInConcatenationRule extends AbstractRule {
    // %s и %d, но не начало слова вроде '%search'
    private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+\\$)?[sd](?![A-Za-z])");
    // String.format, MessageFormat.format, printf, formatted, logger.infof: строку заполнят аргументами
    private static final Pattern FORMATTER =
            Pattern.compile("(?i).*format.*|printf|(trace|debug|info|warn|error|fatal)f");

    @Override
    public String code() {
        return RuleCodes.FORMAT_PLACEHOLDER_IN_CONCATENATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет строку с подстановкой %s, к которой значение приклеено через +, а не передано"
                + " в String.format";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (StringLiteralExpr literal : sourceFile.unit().findAll(StringLiteralExpr.class)) {
            if (!PLACEHOLDER.matcher(literal.getValue()).find() || TestClasses.isInside(literal)) {
                continue;
            }
            Expression concatenation = concatenationOf(literal);
            if (concatenation != literal && hasValue(concatenation) && isOnlyArgument(concatenation)
                    && !isFormatted(concatenation)) {
                violations.add(violation(sourceFile, literal,
                        "В строке " + Nodes.text(literal) + " есть подстановка, но значение приклеено к ней через"
                                + " '+': подстановку никто не заполнит, и она так и останется в тексте; уберите ее"
                                + " либо передайте значение через String.format(...)"));
            }
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

    // Метод, которому передана строка, может заполнять подстановки сам - по одному файлу этого не видно
    @Override
    public Confidence confidence() {
        return Confidence.PROBABLE;
    }

    // Вся цепочка "a" + b + "c", в которую входит строка; сама строка, если ее ни с чем не складывают
    private Expression concatenationOf(Expression literal) {
        Expression top = literal;
        Node parent = top.getParentNode().orElse(null);
        while (parent instanceof EnclosedExpr
                || parent instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS) {
            top = (Expression) parent;
            parent = top.getParentNode().orElse(null);
        }
        return top;
    }

    // Шаблон, собранный из одних строк ("%s, " + "%s"), заполнят позже; здесь к строке приклеено значение
    private boolean hasValue(Expression concatenation) {
        Expression value = Nodes.unwrap(concatenation);
        if (value.isBinaryExpr() && value.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
            return hasValue(value.asBinaryExpr().getLeft()) || hasValue(value.asBinaryExpr().getRight());
        }
        return !value.isLiteralExpr();
    }

    // new SomeException("... %s" + value): других аргументов нет, значит, и заполнять подстановку нечем.
    // Переменную и return не трогаем: такую строку могут отформатировать позже
    private boolean isOnlyArgument(Expression concatenation) {
        return concatenation.getParentNode()
                .filter(parent -> parent instanceof NodeWithArguments<?> call && call.getArguments().size() == 1
                        && call.getArgument(0) == concatenation)
                .isPresent();
    }

    private boolean isFormatted(Expression concatenation) {
        Node current = concatenation.getParentNode().orElse(null);
        while (current != null && !(current instanceof Statement)) {
            if (current instanceof MethodCallExpr call && FORMATTER.matcher(call.getNameAsString()).matches()) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }
}
