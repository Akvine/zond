package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.List;

@Component
public class CheckNestedTernaryRule extends AbstractRule {
    @Override
    public String code() {
        return RuleCodes.CHECK_NESTED_TERNARY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет тернарные операторы, вложенные друг в друга";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // Сообщаем о внешнем операторе, и только один раз на всю цепочку
        return sourceFile.unit().findAll(ConditionalExpr.class).stream()
                .filter(ternary -> !isNested(ternary))
                .filter(ternary -> ternary.findAll(ConditionalExpr.class).size() > 1)
                .map(ternary -> violation(sourceFile, ternary,
                        "Вложенный тернарный оператор: чтобы понять, какое значение получится, условия приходится"
                                + " разбирать по скобкам; замените на if / else или вынесите в метод"))
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

    private boolean isNested(ConditionalExpr ternary) {
        Node parent = ternary.getParentNode().orElse(null);
        while (parent instanceof EnclosedExpr) {
            parent = parent.getParentNode().orElse(null);
        }
        return parent instanceof ConditionalExpr;
    }
}
