package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.RuleParameter;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;

@Component
public class CyclomaticComplexityRule extends AbstractRule {
    private static final RuleParameter MAX_COMPLEXITY =
            new RuleParameter("max-complexity", 10, "Допустимая цикломатическая сложность метода");

    @Override
    public List<RuleParameter> parameters() {
        return List.of(MAX_COMPLEXITY);
    }

    @Override
    public String code() {
        return RuleCodes.CYCLOMATIC_COMPLEXITY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет методы с цикломатической сложностью выше " + value(MAX_COMPLEXITY);
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            if (method.getBody().isEmpty() || TestClasses.isInside(method)) {
                continue;
            }

            int complexity = complexity(method);
            if (complexity > value(MAX_COMPLEXITY)) {
                violations.add(violation(sourceFile, method,
                        "Цикломатическая сложность метода '" + method.getNameAsString() + "' - " + complexity
                                + " при допустимой " + value(MAX_COMPLEXITY) + ": столько независимых путей выполнения"
                                + " нужно удержать в голове и покрыть тестами; разбейте метод на части"));
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
        return ErrorType.CODE_SMELL;
    }

    // Один путь есть всегда; каждое ветвление добавляет еще один
    private int complexity(MethodDeclaration method) {
        long branches = method.findAll(IfStmt.class).size()
                + method.findAll(ForStmt.class).size()
                + method.findAll(ForEachStmt.class).size()
                + method.findAll(WhileStmt.class).size()
                + method.findAll(DoStmt.class).size()
                + method.findAll(CatchClause.class).size()
                + method.findAll(ConditionalExpr.class).size()
                + method.findAll(SwitchEntry.class).stream().filter(entry -> !entry.getLabels().isEmpty()).count()
                + method.findAll(BinaryExpr.class).stream()
                .filter(binary -> binary.getOperator() == BinaryExpr.Operator.AND
                        || binary.getOperator() == BinaryExpr.Operator.OR)
                .count();
        return (int) branches + 1;
    }
}
