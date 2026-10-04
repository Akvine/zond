package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.regex.Pattern;

@Component
public class CheckCastWithoutInstanceofRule extends AbstractRule {
    private static final String OBJECT = "Object";
    private static final String GET_CLASS = "getClass";

    // (T) value: параметр типа проверить через instanceof нельзя
    private static final Pattern TYPE_PARAMETER = Pattern.compile("^[A-Z]$");

    @Override
    public String code() {
        return RuleCodes.CHECK_CAST_WITHOUT_INSTANCEOF_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет приведение Object к конкретному типу без проверки instanceof";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(CastExpr.class).stream()
                .filter(cast -> cast.getType().isClassOrInterfaceType()
                        && !TYPE_PARAMETER.matcher(cast.getType().asClassOrInterfaceType().getNameAsString()).matches())
                .filter(cast -> Nodes.unwrap(cast.getExpression()).isNameExpr())
                .filter(cast -> LocalTypes.typeOf(cast.getExpression()).filter(OBJECT::equals).isPresent())
                .filter(cast -> !TestClasses.isInside(cast))
                .filter(cast -> !isTypeChecked(cast))
                .map(cast -> violation(sourceFile, cast,
                        "'" + cast + "': Object приводится к " + cast.getType() + " без проверки типа - если там"
                                + " окажется другой объект, будет ClassCastException; проверьте через instanceof"))
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

    // value instanceof Type либо сравнение классов, как в equals: getClass() != value.getClass()
    private boolean isTypeChecked(CastExpr cast) {
        String variable = Nodes.unwrap(cast.getExpression()).toString();
        return Guards.isGuarded(cast, check -> isTypeCheck(check, variable));
    }

    private boolean isTypeCheck(Expression check, String variable) {
        if (check.isInstanceOfExpr()) {
            return check.asInstanceOfExpr().getExpression().toString().equals(variable);
        }
        return check.isMethodCallExpr()
                && GET_CLASS.equals(check.asMethodCallExpr().getNameAsString())
                && check.asMethodCallExpr().getScope().filter(scope -> scope.toString().equals(variable)).isPresent();
    }
}
