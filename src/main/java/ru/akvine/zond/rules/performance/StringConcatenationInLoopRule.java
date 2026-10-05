package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
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

@Component
public class StringConcatenationInLoopRule extends AbstractRule {
    private static final String STRING = "String";

    @Override
    public String code() {
        return RuleCodes.STRING_CONCATENATION_IN_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет накопление строки конкатенацией в цикле";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (AssignExpr assign : sourceFile.unit().findAll(AssignExpr.class)) {
            Optional<Node> loop = Nodes.enclosingLoop(assign);
            if (loop.isEmpty() || !assign.getTarget().isNameExpr() || !isAccumulation(assign)) {
                continue;
            }

            // Строка должна быть объявлена до цикла: объявленная внутри живет одну итерацию и не растет
            String name = assign.getTarget().asNameExpr().getNameAsString();
            Optional<Node> declaration = LocalTypes.findDeclaration(assign, name);
            boolean accumulatesString = declaration
                    .filter(node -> !loop.get().isAncestorOf(node))
                    .flatMap(LocalTypes::declaredType)
                    .filter(STRING::equals)
                    .isPresent();

            if (accumulatesString) {
                violations.add(violation(sourceFile, assign,
                        "Строка '" + name + "' накапливается конкатенацией в цикле: на каждой итерации создается"
                                + " новая строка и копируется все накопленное; используйте StringBuilder"));
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
        return ErrorType.PERFORMANCE;
    }

    // s += x либо s = s + x
    private boolean isAccumulation(AssignExpr assign) {
        if (assign.getOperator() == AssignExpr.Operator.PLUS) {
            return true;
        }
        return assign.getOperator() == AssignExpr.Operator.ASSIGN
                && containsOperand(assign.getValue(), assign.getTarget().toString());
    }

    private boolean containsOperand(Expression expression, String name) {
        Expression value = Nodes.unwrap(expression);
        if (value.isBinaryExpr() && value.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
            return containsOperand(value.asBinaryExpr().getLeft(), name)
                    || containsOperand(value.asBinaryExpr().getRight(), name);
        }
        return value.isNameExpr() && value.asNameExpr().getNameAsString().equals(name);
    }
}
