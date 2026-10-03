package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class CheckTransactionalSelfInvocationRule implements Rule {

    @Override
    public String name() {
        return getClass().getSimpleName();
    }

    @Override
    public String code() {
        return RuleCodes.CHECK_TRANSACTIONAL_SELF_INVOCATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет вызовы @Transactional-методов из того же класса (self-invocation)";
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (type.isInterface()) {
                continue;
            }

            // Приватные не учитываем: на них @Transactional не работает в принципе, это ловит отдельное правило
            List<MethodDeclaration> transactionalMethods = type.getMethods().stream()
                    .filter(method -> !method.isPrivate())
                    .filter(TransactionalAnnotations::isPresent)
                    .toList();
            if (transactionalMethods.isEmpty()) {
                continue;
            }

            for (MethodDeclaration caller : type.getMethods()) {
                for (MethodCallExpr call : caller.findAll(MethodCallExpr.class)) {
                    if (!isSelfCall(call, type)) {
                        continue;
                    }
                    findCallee(call, transactionalMethods)
                            .flatMap(callee -> describeProblem(type, caller, callee))
                            .ifPresent(message -> violations.add(new Violation(
                                    errorLevel(),
                                    errorType(),
                                    code(),
                                    name(),
                                    sourceFile.path(),
                                    call.getBegin().map(position -> position.line).orElse(0),
                                    message)));
                }
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    // save() или this.save() прямо в этом классе, а не во вложенном
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

    // Типы не разрешаем, поэтому перегрузки различаем только по числу аргументов
    private Optional<MethodDeclaration> findCallee(MethodCallExpr call, List<MethodDeclaration> candidates) {
        return candidates.stream()
                .filter(method -> method.getNameAsString().equals(call.getNameAsString()))
                .filter(method -> method.getParameters().size() == call.getArguments().size())
                .findFirst();
    }

    private Optional<String> describeProblem(
            ClassOrInterfaceDeclaration type, MethodDeclaration caller, MethodDeclaration callee) {
        String prefix = "Вызов @Transactional-метода '" + callee.getNameAsString() + "' из метода '"
                + caller.getNameAsString() + "' того же класса идет в обход Spring-прокси: ";

        if (!isTransactional(type, caller)) {
            return Optional.of(prefix + "транзакция не будет открыта");
        }

        // Вызывающий метод уже в транзакции: проблема есть, только если вызываемому нужно свое поведение
        return TransactionalAnnotations.find(callee)
                .flatMap(TransactionalAnnotations::findOwnBehaviorPropagation)
                .map(propagation -> prefix + propagation + " не сработает, метод выполнится в транзакции вызывающего");
    }

    private boolean isTransactional(ClassOrInterfaceDeclaration type, MethodDeclaration method) {
        if (method.isPrivate()) {
            return false;
        }
        Optional<AnnotationExpr> onMethod = TransactionalAnnotations.find(method);
        return onMethod.isPresent() || (method.isPublic() && TransactionalAnnotations.isPresent(type));
    }
}
