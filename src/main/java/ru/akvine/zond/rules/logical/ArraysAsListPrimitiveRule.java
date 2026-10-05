package ru.akvine.zond.rules.logical;

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

import java.util.List;
import java.util.Set;

@Component
public class ArraysAsListPrimitiveRule extends AbstractRule {
    private static final String ARRAYS = "Arrays";
    private static final String AS_LIST = "asList";
    private static final Set<String> PRIMITIVE_ARRAYS = Set.of(
            "int[]", "long[]", "double[]", "float[]", "short[]", "byte[]", "char[]", "boolean[]");

    @Override
    public String code() {
        return RuleCodes.ARRAYS_AS_LIST_PRIMITIVE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет Arrays.asList() от массива примитивов";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> MethodCalls.isCallOn(call, ARRAYS, AS_LIST) && call.getArguments().size() == 1)
                .filter(call -> LocalTypes.typeOf(call.getArgument(0)).filter(PRIMITIVE_ARRAYS::contains).isPresent())
                .map(call -> violation(sourceFile, call,
                        "'" + call + "' от массива примитивов: получится список из одного элемента - самого"
                                + " массива, а не список чисел; используйте Arrays.stream(...).boxed().toList()"))
                .toList();
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
