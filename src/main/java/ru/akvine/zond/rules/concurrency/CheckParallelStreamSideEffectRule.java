package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CollectionKinds;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.StreamChains;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckParallelStreamSideEffectRule extends AbstractRule {
    private static final Set<String> PARALLEL_METHODS = Set.of("parallelStream", "parallel");
    private static final Set<String> FOR_EACH_METHODS = Set.of("forEach", "forEachOrdered", "peek", "map", "filter");
    private static final Set<String> MUTATING_METHODS =
            Set.of("add", "addAll", "put", "putAll", "remove", "push", "offer", "append", "merge");

    @Override
    public String code() {
        return RuleCodes.CHECK_PARALLEL_STREAM_SIDE_EFFECT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет запись в обычную коллекцию из параллельного стрима";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr parallel : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!PARALLEL_METHODS.contains(parallel.getNameAsString()) || !parallel.getArguments().isEmpty()) {
                continue;
            }

            StreamChains.callsAfter(parallel).stream()
                    .filter(operation -> FOR_EACH_METHODS.contains(operation.getNameAsString()))
                    .flatMap(operation -> operation.getArguments().stream())
                    .map(this::findSharedCollection)
                    .flatMap(Optional::stream)
                    .findFirst()
                    .ifPresent(collection -> violations.add(violation(sourceFile, parallel,
                            "Параллельный стрим пишет в '" + collection + "' из нескольких потоков: обычная"
                                    + " коллекция к этому не готова - элементы теряются, возможно"
                                    + " ArrayIndexOutOfBoundsException; собирайте результат через collect(...)")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CONCURRENCY;
    }

    /**
     * @return коллекция, в которую пишет действие стрима, если она не рассчитана на несколько потоков
     */
    private Optional<String> findSharedCollection(Expression action) {
        // results::add - парсер хранит results как тип, поэтому переменную ищем по имени
        if (action.isMethodReferenceExpr()) {
            Expression scope = action.asMethodReferenceExpr().getScope();
            if (!MUTATING_METHODS.contains(action.asMethodReferenceExpr().getIdentifier())) {
                return Optional.empty();
            }
            String name = scope.isTypeExpr() ? LocalTypes.typeName(scope.asTypeExpr().getType()) : scope.toString();
            return LocalTypes.findDeclaration(action, name)
                    .filter(declaration -> !CollectionKinds.isThreadSafeDeclaration(declaration))
                    .map(declaration -> name);
        }

        // item -> results.add(item); коллекция должна быть объявлена вне лямбды и не быть потокобезопасной
        return action.findAll(MethodCallExpr.class).stream()
                .filter(call -> MUTATING_METHODS.contains(call.getNameAsString()))
                .map(MethodCallExpr::getScope)
                .flatMap(Optional::stream)
                .filter(scope -> scope.isNameExpr() || scope.isFieldAccessExpr())
                .filter(scope -> LocalTypes.findDeclaration(scope)
                        .filter(declaration -> !action.isAncestorOf(declaration))
                        .isPresent())
                .filter(scope -> !CollectionKinds.isThreadSafe(scope))
                .map(Expression::toString)
                .findFirst();
    }
}
