package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Guards;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckNullUnboxingRule extends AbstractRule {
    private static final String BOOLEAN = "Boolean";

    @Override
    public String code() {
        return RuleCodes.CHECK_NULL_UNBOXING_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет Boolean в условии без проверки на null";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // Об одной переменной в одном методе сообщаем один раз
        Set<Node> reported = new HashSet<>();
        return sourceFile.unit().findAll(NameExpr.class).stream()
                .filter(this::isUsedAsCondition)
                .filter(name -> isNullableBoolean(name) && !TestClasses.isInside(name))
                .filter(name -> !Guards.isGuarded(name, check -> Guards.isNullCheck(check, name.getNameAsString())))
                .filter(name -> LocalTypes.findDeclaration(name).filter(reported::add).isPresent())
                .map(name -> violation(sourceFile, name,
                        "Boolean '" + name.getNameAsString() + "' в условии распаковывается в boolean: если в нем"
                                + " null, будет NullPointerException; используйте Boolean.TRUE.equals("
                                + name.getNameAsString() + ") либо примитивный boolean"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    // if (flag), while (flag), flag ? a : b, !flag, flag && other
    private boolean isUsedAsCondition(NameExpr name) {
        Node parent = name.getParentNode().orElse(null);
        if (parent instanceof IfStmt branch) {
            return branch.getCondition() == name;
        }
        if (parent instanceof WhileStmt loop) {
            return loop.getCondition() == name;
        }
        if (parent instanceof ConditionalExpr ternary) {
            return ternary.getCondition() == name;
        }
        if (parent instanceof UnaryExpr unary) {
            return unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT;
        }
        return parent instanceof BinaryExpr binary
                && (binary.getOperator() == BinaryExpr.Operator.AND || binary.getOperator() == BinaryExpr.Operator.OR);
    }

    // Параметр, поле или переменная, объявленные как Boolean; переменная, которой сразу задан литерал, не null
    private boolean isNullableBoolean(NameExpr name) {
        Optional<Node> declaration = LocalTypes.findDeclaration(name);
        if (declaration.isEmpty() || LocalTypes.declaredType(declaration.get()).filter(BOOLEAN::equals).isEmpty()) {
            return false;
        }
        if (declaration.get() instanceof Parameter) {
            return true;
        }
        return declaration.get() instanceof VariableDeclarator variable
                && variable.getInitializer().map(Nodes::unwrap).filter(Expression::isBooleanLiteralExpr).isEmpty();
    }
}
