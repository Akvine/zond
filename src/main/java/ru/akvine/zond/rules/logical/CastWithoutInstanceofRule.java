package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.Type;
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
public class CastWithoutInstanceofRule extends AbstractRule {
    private static final String OBJECT = "Object";
    private static final String EQUALS = "equals";
    private static final String OVERRIDE = "Override";
    private static final Set<String> CLASS_CHECKS = Set.of("getClass", "isInstance", "getUserClass");
    private static final String CLASS = "Class";

    // (T) value: параметр типа проверить через instanceof нельзя
    private static final Pattern TYPE_PARAMETER = Pattern.compile("^[A-Z]$");

    @Override
    public String code() {
        return RuleCodes.CAST_WITHOUT_INSTANCEOF_RULE_CODE;
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
                .filter(cast -> !isTypeChecked(cast) && !isDeclaredType(cast) && !isFrameworkMessage(cast))
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
        if (!check.isMethodCallExpr()) {
            return false;
        }
        MethodCallExpr call = check.asMethodCallExpr();
        // if (!super.equals(o)) return false;: родительский equals уже сравнил классы
        boolean superEquals = EQUALS.equals(call.getNameAsString())
                && call.getScope().filter(Expression::isSuperExpr).isPresent()
                && call.getArguments().stream().anyMatch(argument -> argument.toString().equals(variable));
        // value.getClass(), Hibernate.getClass(value), Type.class.isInstance(value)
        return superEquals || CLASS_CHECKS.contains(call.getNameAsString())
                && (call.getScope().filter(scope -> scope.toString().equals(variable)).isPresent()
                || call.getArguments().stream().anyMatch(argument -> argument.toString().equals(variable)));
    }

    /**
     * Стратегия сама объявляет, с каким типом работает: {@code Class<Boolean> getType()}. Вызывающий код
     * выбирает ее по этому типу, и приведение (Boolean) object внутри нее безопасно.
     */
    /**
     * decode(Channel channel, SocketAddress address, Object msg) с @Override в декодере Netty: тип сообщения
     * задает конвейер, который собирает сам проект, а сигнатуру - фреймворк. В equals(Object) тип приходит
     * от кого угодно, и проверка там нужна
     */
    private boolean isFrameworkMessage(CastExpr cast) {
        String name = Nodes.unwrap(cast.getExpression()).asNameExpr().getNameAsString();
        return Nodes.enclosingCallable(cast)
                .filter(callable -> callable instanceof MethodDeclaration)
                .map(callable -> (MethodDeclaration) callable)
                .filter(method -> !EQUALS.equals(method.getNameAsString()) && method.isAnnotationPresent(OVERRIDE))
                .filter(method -> method.getParameters().stream()
                        .anyMatch(parameter -> parameter.getNameAsString().equals(name)
                                && OBJECT.equals(LocalTypes.typeName(parameter.getType()))))
                .isPresent();
    }

    private boolean isDeclaredType(CastExpr cast) {
        String target = cast.getType().asClassOrInterfaceType().getNameAsString();
        return cast.findAncestor(ClassOrInterfaceDeclaration.class).stream()
                .flatMap(type -> type.getMethods().stream())
                .map(MethodDeclaration::getType)
                .filter(Type::isClassOrInterfaceType)
                .map(Type::asClassOrInterfaceType)
                .filter(type -> CLASS.equals(type.getNameAsString()))
                .flatMap(type -> type.getTypeArguments().stream().flatMap(List::stream))
                .anyMatch(argument -> LocalTypes.typeName(argument).equals(target));
    }
}
