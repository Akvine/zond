package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Set;

@Component
public class CheckToMapWithoutMergeRule extends AbstractRule {
    private static final String COLLECTORS = "Collectors";
    private static final Set<String> TO_MAP_METHODS = Set.of("toMap", "toConcurrentMap", "toUnmodifiableMap");

    // toMap(keyMapper, valueMapper): третьим аргументом идет функция слияния
    private static final int ARGUMENTS_WITHOUT_MERGE = 2;

    @Override
    public String code() {
        return RuleCodes.CHECK_TO_MAP_WITHOUT_MERGE_RULE_CODE;
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
                .map(call -> violation(sourceFile, call,
                        call.getNameAsString() + "(...) без функции слияния: на первом же повторяющемся ключе будет"
                                + " IllegalStateException (Duplicate key); добавьте третий аргумент, например"
                                + " (left, right) -> left, либо используйте groupingBy"))
                .toList();
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
