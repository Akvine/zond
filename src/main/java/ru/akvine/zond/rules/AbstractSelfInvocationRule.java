package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Вызов метода с аннотацией, которая работает через Spring-прокси, из того же класса:
 * такой вызов идет напрямую, минуя прокси, и аннотация не срабатывает.
 */
public abstract class AbstractSelfInvocationRule extends AbstractRule {

    /**
     * @return простые имена аннотаций, которые работают только через прокси
     */
    protected abstract Set<String> annotations();

    /**
     * @return что именно не произойдет при вызове в обход прокси
     */
    protected abstract String consequence(String annotation);

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (type.isInterface()) {
                continue;
            }

            // На приватных методах прокси не работает в принципе
            List<MethodDeclaration> proxied = type.getMethods().stream()
                    .filter(method -> !method.isPrivate())
                    .filter(method -> Annotations.hasAny(method, annotations()))
                    .toList();
            if (proxied.isEmpty()) {
                continue;
            }

            for (MethodDeclaration caller : type.getMethods()) {
                for (MethodCallExpr call : caller.findAll(MethodCallExpr.class)) {
                    if (!isSelfCall(call, type)) {
                        continue;
                    }
                    MethodCalls.findCallee(call, proxied).ifPresent(callee -> {
                        String annotation = Annotations.find(callee, annotations())
                                .map(AnnotationExpr::getNameAsString)
                                .orElse("");
                        violations.add(violation(sourceFile, call,
                                "Вызов @" + annotation + "-метода '" + callee.getNameAsString() + "' из метода '"
                                        + caller.getNameAsString() + "' того же класса идет в обход Spring-прокси: "
                                        + consequence(annotation)));
                    });
                }
            }
        }
        return violations;
    }

    // load() или this.load() прямо в этом классе, а не во вложенном
    private boolean isSelfCall(MethodCallExpr call, ClassOrInterfaceDeclaration type) {
        boolean ownScope = call.getScope()
                .map(scope -> scope.isThisExpr() && scope.asThisExpr().getTypeName().isEmpty())
                .orElse(true);
        return ownScope && enclosingType(call) == type;
    }

    private Node enclosingType(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null && !(current instanceof TypeDeclaration<?>)) {
            current = current.getParentNode().orElse(null);
        }
        return current;
    }
}
