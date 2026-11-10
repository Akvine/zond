package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class ArraysAsListPrimitiveRule extends AbstractRule {
    private static final String ARRAYS = "Arrays";
    private static final String AS_LIST = "asList";
    private static final String OF = "of";
    // List.of(array), Set.of(array), Stream.of(array): то же самое, что Arrays.asList(array)
    private static final Map<String, String> FACTORIES = Map.of("List", "список", "Set", "множество", "Stream", "стрим");
    private static final Set<String> PRIMITIVE_ARRAYS = Set.of(
            "int[]", "long[]", "double[]", "float[]", "short[]", "byte[]", "char[]", "boolean[]");

    @Override
    public String code() {
        return RuleCodes.ARRAYS_AS_LIST_PRIMITIVE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет Arrays.asList(), List.of(), Set.of() и Stream.of() от массива примитивов";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getArguments().size() == 1 && isPrimitiveArray(call.getArgument(0)))
                .flatMap(call -> container(call)
                        .map(container -> violation(sourceFile, call,
                                "'" + call + "' от массива примитивов: получится " + container + " из одного"
                                        + " элемента - самого массива, а не список чисел; используйте"
                                        + " Arrays.stream(...).boxed().toList()"))
                        .stream())
                .toList();
    }

    // Параметр T... принимает объекты: массив примитивов для него - один объект, а не набор значений
    private Optional<String> container(MethodCallExpr call) {
        if (MethodCalls.isCallOn(call, ARRAYS, AS_LIST)) {
            return Optional.of("список");
        }
        return FACTORIES.entrySet().stream()
                .filter(factory -> MethodCalls.isCallOn(call, factory.getKey(), OF))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    // int[] values либо new int[] {1, 2, 3} прямо в вызове
    private boolean isPrimitiveArray(Expression argument) {
        Expression value = Nodes.unwrap(argument);
        if (value.isArrayCreationExpr()) {
            return value.asArrayCreationExpr().getElementType().isPrimitiveType()
                    && value.asArrayCreationExpr().getLevels().size() == 1;
        }
        return LocalTypes.typeOf(value).filter(PRIMITIVE_ARRAYS::contains).isPresent();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }
}
