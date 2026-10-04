package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckThisEscapeInConstructorRule extends AbstractRule {
    private static final int SNIPPET_LENGTH = 80;

    @Override
    public String code() {
        return RuleCodes.CHECK_THIS_ESCAPE_IN_CONSTRUCTOR_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет утечку this из конструктора: передачу this, this::method или лямбды с this наружу";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ConstructorDeclaration constructor : sourceFile.unit().findAll(ConstructorDeclaration.class)) {
            // Одна лямбда может обращаться к this несколько раз - сообщаем о ней один раз
            Set<Node> reported = Collections.newSetFromMap(new IdentityHashMap<>());

            for (ThisExpr thisExpr : constructor.findAll(ThisExpr.class)) {
                // Outer.this и this внутри анонимного класса - это уже другой объект
                if (thisExpr.getTypeName().isPresent() || isInsideNestedClass(thisExpr, constructor)) {
                    continue;
                }

                findEscape(thisExpr, constructor)
                        .filter(reported::add)
                        .ifPresent(escape -> violations.add(violation(sourceFile, escape,
                                "Утечка this из конструктора '" + constructor.getNameAsString() + "' в '"
                                        + snippet(escape) + "': объект уходит наружу до завершения конструктора,"
                                        + " другой поток может увидеть его недостроенным; публикуйте объект"
                                        + " после создания, например через фабричный метод")));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CONCURRENCY;
    }

    /**
     * @return узел, через который this покидает конструктор, либо пусто
     */
    private Optional<Node> findEscape(ThisExpr thisExpr, ConstructorDeclaration constructor) {
        Node parent = thisExpr.getParentNode().orElse(null);

        // register(this), new Thread(this)
        if (isPassedOutside(thisExpr, parent)) {
            return Optional.of(thisExpr);
        }

        // other.owner = this, INSTANCE = this
        if (isAssignedOutside(thisExpr, parent)) {
            return Optional.of(thisExpr);
        }

        // subscribe(this::handle), other.callback = this::handle
        if (parent instanceof MethodReferenceExpr reference) {
            Node holder = reference.getParentNode().orElse(null);
            return isPassedOutside(reference, holder) || isAssignedOutside(reference, holder)
                    ? Optional.of(reference)
                    : Optional.empty();
        }

        // submit(() -> this.handle()): лямбда захватывает this и уходит наружу
        return findEnclosingLambda(thisExpr, constructor)
                .filter(lambda -> isPassedOutside(lambda, lambda.getParentNode().orElse(null)))
                .map(lambda -> lambda);
    }

    private boolean isPassedOutside(Expression value, Node parent) {
        if (parent instanceof ObjectCreationExpr creation) {
            return isArgument(value, creation.getArguments());
        }
        // listeners.add(this) на собственном поле - объект остается внутри себя
        return parent instanceof MethodCallExpr call
                && isArgument(value, call.getArguments())
                && call.getScope().filter(scope -> isOwnState(scope, false)).isEmpty();
    }

    // Запись в локальную переменную this наружу не выносит
    private boolean isAssignedOutside(Expression value, Node parent) {
        return parent instanceof AssignExpr assign
                && assign.getValue() == value
                && !isOwnState(assign.getTarget(), true);
    }

    private boolean isArgument(Expression value, List<Expression> arguments) {
        return arguments.stream().anyMatch(argument -> argument == value);
    }

    // this.x либо нестатическое поле своего класса; локальные переменные и параметры - по флагу:
    // параметр конструктора - это чужой объект, вызов на нем выносит this наружу
    private boolean isOwnState(Expression expression, boolean localsAreOwn) {
        if (expression.isFieldAccessExpr()) {
            return expression.asFieldAccessExpr().getScope().isThisExpr();
        }
        if (!expression.isNameExpr()) {
            return false;
        }

        Optional<Node> declaration =
                LocalTypes.findDeclaration(expression, expression.asNameExpr().getNameAsString());
        if (declaration.isEmpty()) {
            return false;
        }

        Optional<FieldDeclaration> field = Optional.of(declaration.get())
                .filter(node -> node instanceof VariableDeclarator)
                .flatMap(Node::getParentNode)
                .filter(parent -> parent instanceof FieldDeclaration)
                .map(parent -> (FieldDeclaration) parent);

        // Статическое поле видно всем потокам сразу
        return field.map(own -> !own.isStatic()).orElse(localsAreOwn);
    }

    private Optional<LambdaExpr> findEnclosingLambda(Node node, ConstructorDeclaration constructor) {
        LambdaExpr outermost = null;
        Node current = node.getParentNode().orElse(null);
        while (current != null && current != constructor) {
            if (current instanceof LambdaExpr lambda) {
                outermost = lambda;
            }
            current = current.getParentNode().orElse(null);
        }
        return Optional.ofNullable(outermost);
    }

    private boolean isInsideNestedClass(Node node, ConstructorDeclaration constructor) {
        Node current = node.getParentNode().orElse(null);
        while (current != null && current != constructor) {
            if (current instanceof BodyDeclaration<?>) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    // Для сообщения берем выражение, в котором this уходит наружу: bus.register(this)
    private String snippet(Node escape) {
        String text = escape.getParentNode().map(Node::toString).orElse(escape.toString())
                .replaceAll("\\s+", " ");
        return text.length() > SNIPPET_LENGTH ? text.substring(0, SNIPPET_LENGTH) + "..." : text;
    }
}
