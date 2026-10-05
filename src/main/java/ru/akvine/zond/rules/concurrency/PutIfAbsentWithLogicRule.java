package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.IfStmt;
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
import java.util.Set;
import java.util.function.Predicate;

@Component
public class PutIfAbsentWithLogicRule extends AbstractRule {
    private static final String PUT_IF_ABSENT = "putIfAbsent";
    private static final Set<String> CONCURRENT_MAP_TYPES =
            Set.of("ConcurrentHashMap", "ConcurrentMap", "ConcurrentSkipListMap", "ConcurrentNavigableMap");

    @Override
    public String code() {
        return RuleCodes.PUT_IF_ABSENT_WITH_LOGIC_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет логику, которая ветвится по результату ConcurrentHashMap.putIfAbsent()";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();

        // if (map.putIfAbsent(key, value) == null) { ... }
        for (IfStmt ifStmt : sourceFile.unit().findAll(IfStmt.class)) {
            findNullCheck(ifStmt, this::isPutIfAbsent)
                    .ifPresent(check -> violations.add(report(sourceFile, ifStmt)));
        }

        // V previous = map.putIfAbsent(key, value); if (previous == null) { ... }
        for (VariableDeclarator variable : sourceFile.unit().findAll(VariableDeclarator.class)) {
            Optional<Node> callable = Nodes.enclosingCallable(variable);
            if (callable.isEmpty() || variable.getInitializer().filter(this::isPutIfAbsent).isEmpty()) {
                continue;
            }

            String name = variable.getNameAsString();
            for (IfStmt ifStmt : callable.get().findAll(IfStmt.class)) {
                findNullCheck(ifStmt, operand -> operand.isNameExpr()
                        && operand.asNameExpr().getNameAsString().equals(name))
                        .ifPresent(check -> violations.add(report(sourceFile, ifStmt)));
            }
        }

        violations.sort((left, right) -> Integer.compare(left.line(), right.line()));
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CONCURRENCY;
    }

    private Violation report(SourceFile sourceFile, IfStmt ifStmt) {
        return violation(sourceFile, ifStmt,
                "Логика по результату putIfAbsent в '" + ifStmt.getCondition() + "': вставка атомарна, а вставка"
                        + " вместе с последующими действиями - нет, другие потоки увидят запись раньше, чем эти"
                        + " действия завершатся; перенесите логику в computeIfAbsent(...) или compute(...)");
    }

    // Сравнение с null в условии, один из операндов которого подходит под проверку
    private Optional<BinaryExpr> findNullCheck(IfStmt ifStmt, Predicate<Expression> operand) {
        return ifStmt.getCondition().findAll(BinaryExpr.class).stream()
                .filter(binary -> binary.getOperator() == BinaryExpr.Operator.EQUALS
                        || binary.getOperator() == BinaryExpr.Operator.NOT_EQUALS)
                .filter(binary -> {
                    Expression left = Nodes.unwrap(binary.getLeft());
                    Expression right = Nodes.unwrap(binary.getRight());
                    return (left.isNullLiteralExpr() && operand.test(right))
                            || (right.isNullLiteralExpr() && operand.test(left));
                })
                .findFirst();
    }

    private boolean isPutIfAbsent(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        return value.isMethodCallExpr()
                && PUT_IF_ABSENT.equals(value.asMethodCallExpr().getNameAsString())
                && value.asMethodCallExpr().getScope().filter(this::isConcurrentMap).isPresent();
    }

    // По объявленному типу либо по инициализатору: Map<K, V> cache = new ConcurrentHashMap<>()
    private boolean isConcurrentMap(Expression scope) {
        if (LocalTypes.typeOf(scope).filter(CONCURRENT_MAP_TYPES::contains).isPresent()) {
            return true;
        }
        return findDeclaration(Nodes.unwrap(scope))
                .filter(declaration -> declaration instanceof VariableDeclarator)
                .flatMap(declaration -> ((VariableDeclarator) declaration).getInitializer())
                .filter(Expression::isObjectCreationExpr)
                .map(initializer -> initializer.asObjectCreationExpr().getType().getNameAsString())
                .filter(CONCURRENT_MAP_TYPES::contains)
                .isPresent();
    }

    private Optional<Node> findDeclaration(Expression scope) {
        if (scope.isNameExpr()) {
            return LocalTypes.findDeclaration(scope, scope.asNameExpr().getNameAsString());
        }
        if (scope.isFieldAccessExpr() && scope.asFieldAccessExpr().getScope().isThisExpr()) {
            return LocalTypes.findField(scope, scope.asFieldAccessExpr().getNameAsString()).map(field -> field);
        }
        return Optional.empty();
    }
}
