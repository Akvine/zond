package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.List;
import java.util.Optional;

@Component
public class KeySetWithGetRule extends AbstractRule {
    private static final String KEY_SET = "keySet";
    private static final String GET = "get";

    @Override
    public String code() {
        return RuleCodes.KEY_SET_WITH_GET_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет обход keySet() с get() по каждому ключу вместо entrySet()";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(ForEachStmt.class).stream()
                .filter(this::getsValueByEachKey)
                .map(loop -> violation(sourceFile, loop,
                        "Обход '" + loop.getIterable() + "' с get() по каждому ключу: значение ищется в Map"
                                + " заново на каждой итерации; обходите entrySet() - ключ и значение приходят вместе"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    // for (K key : map.keySet()) { ... map.get(key) ... }
    private boolean getsValueByEachKey(ForEachStmt loop) {
        Expression iterable = Nodes.unwrap(loop.getIterable());
        Optional<String> map = Optional.of(iterable)
                .filter(Expression::isMethodCallExpr)
                .map(Expression::asMethodCallExpr)
                .filter(call -> KEY_SET.equals(call.getNameAsString()) && call.getArguments().isEmpty())
                .flatMap(MethodCallExpr::getScope)
                .map(Expression::toString);
        if (map.isEmpty()) {
            return false;
        }
        String key = loop.getVariable().getVariable(0).getNameAsString();
        return loop.getBody().findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> GET.equals(call.getNameAsString())
                        && call.getArguments().size() == 1
                        && call.getArgument(0).toString().equals(key)
                        && call.getScope().filter(scope -> scope.toString().equals(map.get())).isPresent());
    }
}
