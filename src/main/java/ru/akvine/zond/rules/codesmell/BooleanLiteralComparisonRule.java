package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.UnaryExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.List;

@Component
public class BooleanLiteralComparisonRule extends AbstractRule implements ProjectRule {
    // С какого числа 'x == false' это уже соглашение проекта, а не случайность
    private static final int CONVENTION_MIN_USAGES = 10;

    @Override
    public String code() {
        return RuleCodes.BOOLEAN_LITERAL_COMPARISON_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет сравнение с true и false";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        boolean negationByComparison = prefersComparisonWithFalse(sourceFiles);
        return sourceFiles.stream()
                .flatMap(sourceFile -> sourceFile.unit().findAll(BinaryExpr.class).stream()
                        .filter(this::comparesWithLiteral)
                        .filter(comparison -> !negationByComparison || !isComparisonWithFalse(comparison))
                        .filter(comparison -> !TestClasses.isInside(comparison))
                        .map(comparison -> violation(sourceFile, comparison,
                                "'" + comparison + "': сравнение с литералом ничего не добавляет к самому условию"
                                        + " и мешает его читать; пишите условие как есть либо с отрицанием")))
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

    private boolean comparesWithLiteral(BinaryExpr comparison) {
        return (comparison.getOperator() == BinaryExpr.Operator.EQUALS
                || comparison.getOperator() == BinaryExpr.Operator.NOT_EQUALS)
                && (comparison.getLeft().isBooleanLiteralExpr() || comparison.getRight().isBooleanLiteralExpr());
    }

    private boolean isComparisonWithFalse(BinaryExpr comparison) {
        return comparison.getOperator() == BinaryExpr.Operator.EQUALS
                && (isFalse(comparison.getLeft()) || isFalse(comparison.getRight()));
    }

    private boolean isFalse(Expression expression) {
        return expression.isBooleanLiteralExpr() && !expression.asBooleanLiteralExpr().getValue();
    }

    /**
     * Elasticsearch и проекты с тем же стилем пишут отрицание как 'foo == false': восклицательный знак легко
     * не заметить. Если в проекте так написано почти каждое отрицание, это соглашение, а не лишнее сравнение
     */
    private boolean prefersComparisonWithFalse(List<SourceFile> sourceFiles) {
        long comparisons = 0;
        long complements = 0;
        for (SourceFile sourceFile : sourceFiles) {
            comparisons += sourceFile.unit().findAll(BinaryExpr.class).stream()
                    .filter(this::isComparisonWithFalse)
                    .count();
            complements += sourceFile.unit().findAll(UnaryExpr.class).stream()
                    .filter(unary -> unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT)
                    .count();
        }
        return comparisons >= CONVENTION_MIN_USAGES && comparisons > complements;
    }
}
