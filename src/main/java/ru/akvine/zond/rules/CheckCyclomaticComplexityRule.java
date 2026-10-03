package ru.akvine.zond.rules;

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
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;

@Component
public class CheckCyclomaticComplexityRule extends AbstractRule {
    private static final int MAX_COMPLEXITY = 10;

    @Override
    public String code() {
        return RuleCodes.CHECK_CYCLOMATIC_COMPLEXITY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет методы с цикломатической сложностью выше " + MAX_COMPLEXITY;
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            if (method.getBody().isEmpty() || TestClasses.isInside(method)) {
                continue;
            }

            int complexity = complexity(method);
            if (complexity > MAX_COMPLEXITY) {
                violations.add(violation(sourceFile, method,
                        "Цикломатическая сложность метода '" + method.getNameAsString() + "' - " + complexity
                                + " при допустимой " + MAX_COMPLEXITY + ": столько независимых путей выполнения"
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
