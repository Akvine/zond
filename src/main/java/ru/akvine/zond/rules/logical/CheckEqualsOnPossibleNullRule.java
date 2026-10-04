package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Guards;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class CheckEqualsOnPossibleNullRule extends AbstractRule {
    private static final Set<String> EQUALS_METHODS = Set.of("equals", "equalsIgnoreCase");
    private static final Pattern GETTER = Pattern.compile("^get[A-Z].*");

    @Override
    public String code() {
        return RuleCodes.CHECK_EQUALS_ON_POSSIBLE_NULL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет equals со строкой-литералом у значения, которое может быть null";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> EQUALS_METHODS.contains(call.getNameAsString()) && call.getArguments().size() == 1)
                .filter(call -> call.getArgument(0).isStringLiteralExpr() && call.getScope().isPresent())
                .filter(call -> mayBeNull(call.getScope().get()) && !TestClasses.isInside(call))
                .filter(call -> !isNullChecked(call))
                .map(call -> violation(sourceFile, call,
                        "'" + call + "': если '" + call.getScope().get() + "' окажется null, будет"
                                + " NullPointerException; поменяйте местами - " + call.getArgument(0) + "."
                                + call.getNameAsString() + "(" + call.getScope().get() + ")"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    // Значение пришло извне: параметр метода либо свойство другого объекта (order.getStatus()).
    // toString(), trim() и подобные null не возвращают; локальная переменная и поле - на совести автора
    private boolean mayBeNull(Expression scope) {
        Expression value = Nodes.unwrap(scope);
        if (value.isMethodCallExpr()) {
            return GETTER.matcher(value.asMethodCallExpr().getNameAsString()).matches()
                    && value.asMethodCallExpr().getArguments().isEmpty();
        }
        return value.isNameExpr() && LocalTypes.findDeclaration(value)
                .filter(declaration -> declaration instanceof Parameter)
                .isPresent();
    }

    private boolean isNullChecked(MethodCallExpr call) {
        String value = call.getScope().get().toString();
        return Guards.isGuarded(call, check -> Guards.isNullCheck(check, value));
    }
}
