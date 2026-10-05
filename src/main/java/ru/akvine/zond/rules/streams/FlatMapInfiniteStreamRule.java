package ru.akvine.zond.rules.streams;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.StreamChains;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class FlatMapInfiniteStreamRule extends AbstractRule {
    private static final Set<String> FLAT_MAP_METHODS =
            Set.of("flatMap", "flatMapToInt", "flatMapToLong", "flatMapToDouble");

    // flatMap ленив: если дальше по цепочке стоит такая операция, стрим остановится сам
    private static final Set<String> SHORT_CIRCUIT_OPERATIONS = Set.of(
            "limit", "takeWhile", "findFirst", "findAny", "anyMatch", "allMatch", "noneMatch");

    @Override
    public String code() {
        return RuleCodes.FLAT_MAP_INFINITE_STREAM_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет flatMap(), который возвращает бесконечный стрим";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr flatMap : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!FLAT_MAP_METHODS.contains(flatMap.getNameAsString())
                    || flatMap.getArguments().size() != 1
                    || !flatMap.getArgument(0).isLambdaExpr()) {
                continue;
            }

            boolean stopsLater = StreamChains.callsAfter(flatMap).stream()
                    .anyMatch(call -> SHORT_CIRCUIT_OPERATIONS.contains(call.getNameAsString()));
            if (stopsLater) {
                continue;
            }

            returnedExpressions(flatMap.getArgument(0).asLambdaExpr()).stream()
                    .filter(this::isInfinite)
                    .findFirst()
                    .ifPresent(stream -> violations.add(violation(sourceFile, flatMap,
                            flatMap.getNameAsString() + "(...) возвращает бесконечный стрим '" + stream
                                    + "': обработка первого же элемента никогда не завершится;"
                                    + " ограничьте вложенный стрим через limit() или takeWhile()")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.STREAM;
    }

    // item -> expression либо item -> { ...; return expression; }
    private List<Expression> returnedExpressions(LambdaExpr lambda) {
        if (lambda.getExpressionBody().isPresent()) {
            return List.of(lambda.getExpressionBody().get());
        }
        return lambda.getBody().findAll(ReturnStmt.class).stream()
                .filter(returnStmt -> !Nodes.isInNestedScope(returnStmt, lambda))
                .flatMap(returnStmt -> returnStmt.getExpression().stream())
                .toList();
    }

    // Stream.generate(...) без limit прямо в лямбде либо переменная, в которую такой стрим записан
    private boolean isInfinite(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (value.isNameExpr()) {
            return LocalTypes.findDeclaration(value, value.asNameExpr().getNameAsString())
                    .filter(declaration -> declaration instanceof VariableDeclarator)
                    .flatMap(declaration -> ((VariableDeclarator) declaration).getInitializer())
                    .filter(StreamChains::isUnbounded)
                    .isPresent();
        }
        return StreamChains.isUnbounded(value);
    }
}
