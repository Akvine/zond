package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckCheckThenActRule extends AbstractRule {
    private static final String CONTAINS_KEY = "containsKey";
    private static final String GET = "get";
    private static final Set<String> ACTIONS = Set.of("put", "remove", "replace");

    @Override
    public String code() {
        return RuleCodes.CHECK_CHECK_THEN_ACT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет на конкурентной карте проверку и действие отдельными вызовами";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (IfStmt ifStmt : sourceFile.unit().findAll(IfStmt.class)) {
            Optional<Expression> map = findCheckedMap(ifStmt.getCondition());
            if (map.isEmpty() || !CollectionKinds.isConcurrentMap(map.get())) {
                continue;
            }

            String name = map.get().toString();
            boolean acts = ifStmt.findAll(MethodCallExpr.class).stream()
                    .filter(call -> ACTIONS.contains(call.getNameAsString()))
                    .anyMatch(call -> call.getScope().filter(scope -> scope.toString().equals(name)).isPresent());
            if (acts) {
                violations.add(violation(sourceFile, ifStmt,
                        "Проверка и действие на '" + name + "' отдельными вызовами: каждый вызов потокобезопасен,"
                                + " а пара - нет, между ними другой поток успеет изменить карту; используйте"
                                + " одну атомарную операцию: putIfAbsent, computeIfAbsent, compute, remove(key, value)"));
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
        return ErrorType.CONCURRENCY;
    }

    // map.containsKey(key), !map.containsKey(key), map.get(key) == null
    private Optional<Expression> findCheckedMap(Expression condition) {
        for (MethodCallExpr call : condition.findAll(MethodCallExpr.class)) {
            if (CONTAINS_KEY.equals(call.getNameAsString()) || isNullComparedGet(call)) {
                return call.getScope();
            }
        }
        return Optional.empty();
    }

    private boolean isNullComparedGet(MethodCallExpr call) {
        return GET.equals(call.getNameAsString())
                && call.getParentNode()
                .filter(parent -> parent instanceof BinaryExpr binary
                        && (binary.getLeft().isNullLiteralExpr() || binary.getRight().isNullLiteralExpr()))
                .isPresent();
    }
}
