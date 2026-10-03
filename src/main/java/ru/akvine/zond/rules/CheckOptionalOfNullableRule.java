package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Set;

@Component
public class CheckOptionalOfNullableRule extends AbstractRule {
    private static final String OPTIONAL = "Optional";
    private static final String OF = "of";
    private static final String MAP_GET = "get";
    private static final String MAP_SUFFIX = "Map";
    private static final String FIND_PREFIX = "find";

    // Типы не разрешаем, поэтому методы, которые умеют возвращать null, узнаем по именам
    private static final Set<String> NULLABLE_METHODS = Set.of(
            "poll", "peek", "remove", "getProperty", "getenv", "getParameter", "getHeader", "getAttribute",
            "getResource", "getResourceAsStream", "getCause", "getFirstHeader", "getCookies");

    @Override
    public String code() {
        return RuleCodes.CHECK_OPTIONAL_OF_NULLABLE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет Optional.of() от значения, которое может оказаться null";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> MethodCalls.isCallOn(call, OPTIONAL, OF) && call.getArguments().size() == 1)
                .filter(call -> mayBeNull(call.getArgument(0)))
                .map(call -> violation(sourceFile, call,
                        "'" + call + "': аргумент может оказаться null, и вместо пустого Optional будет"
                                + " NullPointerException; используйте Optional.ofNullable(...)"))
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

    // null, map.get(key), repository.findByName(...), System.getenv(...)
    private boolean mayBeNull(Expression argument) {
        Expression value = Nodes.unwrap(argument);
        if (value.isNullLiteralExpr()) {
            return true;
        }
        if (!value.isMethodCallExpr() || value.asMethodCallExpr().getScope().isEmpty()) {
            return false;
        }

        MethodCallExpr call = value.asMethodCallExpr();
        String name = call.getNameAsString();
        return NULLABLE_METHODS.contains(name) || name.startsWith(FIND_PREFIX) || isMapGet(call);
    }

    // get(key) именно на Map: list.get(index) null не возвращает
    private boolean isMapGet(MethodCallExpr call) {
        return MAP_GET.equals(call.getNameAsString())
                && call.getArguments().size() == 1
                && call.getScope()
                .flatMap(LocalTypes::typeOf)
                .filter(type -> type.endsWith(MAP_SUFFIX))
                .isPresent();
    }
}
