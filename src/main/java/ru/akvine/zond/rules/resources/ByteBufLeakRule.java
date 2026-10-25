package ru.akvine.zond.rules.resources;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.List;
import java.util.Set;

/**
 * ByteBuf buf = ctx.alloc().buffer(); ... без buf.release(): буфер из пула Netty считает ссылки, и пока счетчик
 * не дойдет до нуля, память к пулу не вернется. Такой буфер нужно освободить либо отдать дальше - записать в
 * канал, вернуть из метода, передать в другой метод или объект, которые за него отвечают
 */
@Component
public class ByteBufLeakRule extends AbstractRule {
    // Буферы из пула и копии с увеличенным счетчиком ссылок
    private static final Set<String> ALLOCATING_METHODS = Set.of(
            "buffer", "directBuffer", "heapBuffer", "ioBuffer", "compositeBuffer", "compositeDirectBuffer",
            "readRetainedSlice", "retainedSlice", "retainedDuplicate");
    private static final Set<String> RELEASING_METHODS = Set.of("release", "safeRelease", "releaseLater");
    private static final String BYTE_BUF = "ByteBuf";
    // Unpooled.buffer() и Unpooled.directBuffer() пулом не управляются, но directBuffer держит память вне кучи
    private static final String UNPOOLED = "Unpooled";
    private static final String UNPOOLED_DIRECT = "directBuffer";

    @Override
    public String code() {
        return RuleCodes.BYTE_BUF_LEAK_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет ByteBuf из пула Netty, который не освобождают и никуда не передают";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(VariableDeclarator.class).stream()
                .filter(variable -> variable.getType().asString().startsWith(BYTE_BUF))
                .filter(variable -> variable.getInitializer().filter(this::allocates).isPresent())
                .filter(variable -> !TestClasses.isInside(variable))
                .filter(variable -> Nodes.enclosingCallable(variable)
                        .filter(callable -> !escapes(variable.getNameAsString(), callable)).isPresent())
                .map(variable -> violation(sourceFile, variable,
                        "Буфер '" + variable.getNameAsString() + "' взят из пула Netty, но его не освобождают и"
                                + " никуда не передают: память не вернется в пул, и под нагрузкой ее станет не"
                                + " хватать (LEAK в логе Netty); вызовите " + variable.getNameAsString()
                                + ".release() в finally либо отдайте буфер в канал"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.RESOURCE;
    }

    private boolean allocates(Expression initializer) {
        Expression value = Nodes.unwrap(initializer);
        if (!(value instanceof MethodCallExpr call) || !ALLOCATING_METHODS.contains(call.getNameAsString())) {
            return false;
        }
        boolean unpooled = call.getScope().filter(scope -> UNPOOLED.equals(scope.toString())).isPresent();
        return !unpooled || UNPOOLED_DIRECT.equals(call.getNameAsString());
    }

    // Освобожден, возвращен, передан аргументом, записан в поле или другую переменную, захвачен лямбдой
    private boolean escapes(String name, Node callable) {
        for (NameExpr usage : callable.findAll(NameExpr.class)) {
            if (!usage.getNameAsString().equals(name)) {
                continue;
            }
            // return ok ? buf : null, (Object) buf, (buf): значение уходит туда же, куда и все выражение
            Node value = usage;
            Node parent = usage.getParentNode().orElse(null);
            while (parent instanceof EnclosedExpr || parent instanceof CastExpr || parent instanceof ConditionalExpr) {
                value = parent;
                parent = parent.getParentNode().orElse(null);
            }
            if (parent instanceof MethodCallExpr call) {
                boolean released = call.getScope().filter(scope -> scope == usage).isPresent()
                        && RELEASING_METHODS.contains(call.getNameAsString());
                if (released || call.getArguments().contains(value)) {
                    return true;
                }
            }
            if (parent instanceof ObjectCreationExpr || parent instanceof ReturnStmt
                    || parent instanceof AssignExpr assignment && assignment.getValue() == value
                    || parent instanceof VariableDeclarator
                    || usage.findAncestor(LambdaExpr.class)
                    .filter(lambda -> callable.isAncestorOf(lambda)).isPresent()) {
                return true;
            }
        }
        return false;
    }
}
