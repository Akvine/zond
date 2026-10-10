package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
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
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Component
public class CyclomaticComplexityRule extends AbstractRule {
    private static final RuleParameter MAX_COMPLEXITY =
            new RuleParameter("max-complexity", 10, "Допустимая цикломатическая сложность метода");
    private static final String METHOD = "метода";
    private static final String CONSTRUCTOR = "конструктора";

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
        return "Сканирует код и ищет методы и конструкторы с цикломатической сложностью выше " + value(MAX_COMPLEXITY);
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            if (method.getBody().isPresent()) {
                report(sourceFile, method, METHOD, method.getNameAsString(), violations);
            }
        }
        // Конструктор ветвится так же, как метод
        for (ConstructorDeclaration constructor : sourceFile.unit().findAll(ConstructorDeclaration.class)) {
            report(sourceFile, constructor, CONSTRUCTOR, constructor.getNameAsString(), violations);
        }
        violations.sort(Comparator.comparingInt(Violation::line));
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

    private void report(SourceFile sourceFile, Node callable, String kind, String name, List<Violation> violations) {
        if (TestClasses.isInside(callable)) {
            return;
        }
        int complexity = complexity(callable);
        if (complexity > value(MAX_COMPLEXITY)) {
            violations.add(violation(sourceFile, callable,
                    "Цикломатическая сложность " + kind + " '" + name + "' - " + complexity
                            + " при допустимой " + value(MAX_COMPLEXITY) + ": столько независимых путей выполнения"
                            + " нужно удержать в голове и покрыть тестами; разбейте код на части"));
        }
    }

    // Один путь есть всегда; каждое ветвление добавляет еще один: условие, цикл, catch, ветка switch,
    // тернарный оператор и каждое && и || в условиях
    private int complexity(Node callable) {
        long branches = own(callable, IfStmt.class).count()
                + own(callable, ForStmt.class).count()
                + own(callable, ForEachStmt.class).count()
                + own(callable, WhileStmt.class).count()
                + own(callable, DoStmt.class).count()
                + own(callable, CatchClause.class).count()
                + own(callable, ConditionalExpr.class).count()
                // default - не отдельное условие, а путь "все остальное": он уже посчитан
                + own(callable, SwitchEntry.class).filter(entry -> !entry.getLabels().isEmpty()).count()
                + own(callable, BinaryExpr.class)
                .filter(binary -> binary.getOperator() == BinaryExpr.Operator.AND
                        || binary.getOperator() == BinaryExpr.Operator.OR)
                .count();
        return (int) branches + 1;
    }

    // Ветвления самого метода вместе с его лямбдами. Методы анонимного и локального класса, объявленных
    // внутри, - отдельные методы со своей сложностью: дважды их ветвления не считаются
    private <T extends Node> Stream<T> own(Node callable, Class<T> kind) {
        return callable.findAll(kind).stream()
                .filter(node -> Nodes.enclosingCallable(node).filter(owner -> owner == callable).isPresent());
    }
}
