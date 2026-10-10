package ru.akvine.zond.rules.streams;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.MethodCalls;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class ToMapWithoutMergeRule extends AbstractRule {
    private static final String COLLECTORS = "Collectors";
    private static final Set<String> TO_MAP_METHODS = Set.of("toMap", "toConcurrentMap", "toUnmodifiableMap");

    // toMap(keyMapper, valueMapper): третьим аргументом идет функция слияния
    private static final int ARGUMENTS_WITHOUT_MERGE = 2;
    private static final String ENTRY_KEY = "getKey";
    private static final Set<String> IDENTIFIER_GETTERS = Set.of("getId", "getUuid", "id", "uuid");

    @Override
    public String code() {
        return RuleCodes.TO_MAP_WITHOUT_MERGE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет Collectors.toMap() без функции слияния для повторяющихся ключей";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // Collectors.toMap(...) либо toMap(...) через статический импорт; mapper.toMap(a, b) сюда не попадает
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> TO_MAP_METHODS.contains(call.getNameAsString()))
                .filter(call -> call.getArguments().size() == ARGUMENTS_WITHOUT_MERGE)
                .filter(call -> call.getScope().map(scope -> MethodCalls.isType(scope, COLLECTORS)).orElse(true))
                // При запуске повтор ключа - ошибка настройки (два обработчика одного типа, два значения
                // перечисления с одним кодом): падение сразу и есть нужное поведение
                .filter(call -> !CodeContexts.isStartup(call))
                // Ключи записей другой Map повторяться не могут
                .filter(call -> keyGetter(call).filter(ENTRY_KEY::equals).isEmpty())
                .map(call -> report(sourceFile, call))
                .toList();
    }

    // Идентификатор у разных объектов разный: повтор возможен, только если в поток дважды попал один объект
    private Violation report(SourceFile sourceFile, MethodCallExpr call) {
        Violation violation = describe(sourceFile, call);
        return keyGetter(call).filter(IDENTIFIER_GETTERS::contains).isPresent()
                ? violation.withConfidence(Confidence.SUSPICION)
                : violation;
    }

    // Order::getId либо order -> order.getId()
    private Optional<String> keyGetter(MethodCallExpr call) {
        Expression key = call.getArgument(0);
        if (key.isMethodReferenceExpr()) {
            return Optional.of(key.asMethodReferenceExpr().getIdentifier());
        }
        return Optional.of(key)
                .filter(Expression::isLambdaExpr)
                .flatMap(lambda -> lambda.asLambdaExpr().getExpressionBody())
                .filter(body -> body.isMethodCallExpr() && body.asMethodCallExpr().getArguments().isEmpty())
                .map(body -> body.asMethodCallExpr().getNameAsString());
    }

    private Violation describe(SourceFile sourceFile, MethodCallExpr call) {
        return violation(sourceFile, call,
                        call.getNameAsString() + "(...) без функции слияния: на первом же повторяющемся ключе будет"
                                + " IllegalStateException (Duplicate key); добавьте третий аргумент, например"
                                + " (left, right) -> left, либо используйте groupingBy");
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.STREAM;
    }
}
