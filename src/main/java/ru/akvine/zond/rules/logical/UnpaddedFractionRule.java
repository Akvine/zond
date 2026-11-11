package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import org.springframework.stereotype.Component;
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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * kopecks / 100 + "." + kopecks % 100: дробная часть выводится как число, без ведущих нулей - 1005 копеек
 * превращаются в "10.5" вместо "10.05".
 */
@Component
public class UnpaddedFractionRule extends AbstractRule {
    // Остаток от 10 - одна цифра, ей нули не нужны; от 100 и больше - уже нужны
    private static final Pattern WIDE_SCALE = Pattern.compile("1(_?0){2,}[lL]?");
    private static final Set<String> SEPARATORS = Set.of(".", ",");

    @Override
    public String code() {
        return RuleCodes.UNPADDED_FRACTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет дробную часть числа, которую приклеивают после точки остатком от деления"
                + " без ведущих нулей";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (BinaryExpr remainder : sourceFile.unit().findAll(BinaryExpr.class)) {
            if (remainder.getOperator() != BinaryExpr.Operator.REMAINDER
                    || !WIDE_SCALE.matcher(Nodes.unwrap(remainder.getRight()).toString()).matches()
                    || TestClasses.isInside(remainder)) {
                continue;
            }
            if (isGluedAfterSeparator(remainder)) {
                violations.add(violation(sourceFile, remainder,
                        "Дробная часть '" + Nodes.text(remainder) + "' приклеена после разделителя как есть: остаток"
                                + " 5 выведется как \".5\", а не \".05\"; дополняйте нулями -"
                                + " String.format(\"%d.%02d\", целая, дробная)"));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    // ... + "." + value % 100: слева от остатка в той же цепочке стоит строка-разделитель
    private boolean isGluedAfterSeparator(BinaryExpr remainder) {
        Node operand = remainder;
        Node parent = operand.getParentNode().orElse(null);
        while (parent instanceof EnclosedExpr) {
            operand = parent;
            parent = operand.getParentNode().orElse(null);
        }
        if (!(parent instanceof BinaryExpr concatenation) || concatenation.getOperator() != BinaryExpr.Operator.PLUS
                || concatenation.getRight() != operand) {
            return false;
        }
        Expression before = Nodes.unwrap(concatenation.getLeft());
        while (before.isBinaryExpr() && before.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
            before = Nodes.unwrap(before.asBinaryExpr().getRight());
        }
        return before.isStringLiteralExpr() && SEPARATORS.contains(before.asStringLiteralExpr().getValue());
    }
}
