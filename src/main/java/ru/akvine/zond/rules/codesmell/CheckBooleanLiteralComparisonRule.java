package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.expr.BinaryExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.List;

@Component
public class CheckBooleanLiteralComparisonRule extends AbstractRule {
    @Override
    public String code() {
        return RuleCodes.CHECK_BOOLEAN_LITERAL_COMPARISON_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет сравнение с true и false";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(BinaryExpr.class).stream()
                .filter(comparison -> comparison.getOperator() == BinaryExpr.Operator.EQUALS
                        || comparison.getOperator() == BinaryExpr.Operator.NOT_EQUALS)
                .filter(comparison -> comparison.getLeft().isBooleanLiteralExpr()
                        || comparison.getRight().isBooleanLiteralExpr())
                .filter(comparison -> !TestClasses.isInside(comparison))
                .map(comparison -> violation(sourceFile, comparison,
                        "'" + comparison + "': сравнение с литералом ничего не добавляет к самому условию"
                                + " и мешает его читать; пишите условие как есть либо с отрицанием"))
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
}
