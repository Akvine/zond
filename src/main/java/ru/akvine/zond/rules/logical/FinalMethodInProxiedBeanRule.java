package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.ProxyAnnotations;
import ru.akvine.zond.rules.support.TransactionalAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * final в бине, для которого Spring создает прокси. Прокси - наследник класса, созданный CGLIB: от final класса
 * его не создать, а final метод он не может переопределить. Вызов такого метода через бин выполняется на самом
 * объекте-прокси, а не на настоящем объекте. Прокси создается без вызова конструктора, и его поля пусты:
 * обращение к внедренной зависимости заканчивается NullPointerException.
 * <p>
 * О {@code @Transactional} на final методе и в final классе сообщает отдельное правило; здесь - остальные
 * аннотации и final методы без аннотаций.
 */
@Component
public class FinalMethodInProxiedBeanRule extends AbstractRule {
    // find() -> requireActive() -> repository: дальше закрытые методы обычно не уходят
    private static final int MAX_CALL_DEPTH = 3;

    @Override
    public String code() {
        return RuleCodes.FINAL_METHOD_IN_PROXIED_BEAN_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет final классы и открытые final методы в бинах, для которых Spring создает прокси"
                + " (@Transactional, @Async, @Retryable, @Cacheable, @Validated): поля прокси пусты";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            Optional<String> proxyReason = type.isInterface() ? Optional.empty() : proxyReason(type);
            if (proxyReason.isEmpty()) {
                continue;
            }
            // От final класса прокси не создать вообще - отдельные методы уже не важны
            if (type.isFinal()) {
                if (!isTransactional(type)) {
                    violations.add(violation(sourceFile, type,
                            "final класс '" + type.getNameAsString() + "' с " + proxyReason.get() + ": Spring создает"
                                    + " прокси наследованием (CGLIB), от final класса это невозможно - приложение"
                                    + " не стартует либо аннотация не сработает; уберите final"));
                }
                continue;
            }
            for (MethodDeclaration method : type.getMethods()) {
                // @Transactional на final методе - находка отдельного правила
                if (method.isFinal() && method.isPublic() && !method.isStatic()
                        && TransactionalAnnotations.findEffective(method).isEmpty()) {
                    check(sourceFile, type, method, proxyReason.get(), violations);
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

    private void check(
            SourceFile sourceFile, ClassOrInterfaceDeclaration type, MethodDeclaration method, String proxyReason,
            List<Violation> violations) {
        Optional<String> field = fieldUsedIn(method, type, MAX_CALL_DEPTH, Collections.newSetFromMap(new IdentityHashMap<>()));
        Optional<AnnotationExpr> own = ProxyAnnotations.effectiveBehavior(method).or(() -> ProxyAnnotations.onMethod(method));
        String name = method.getNameAsString();
        if (own.isPresent()) {
            violations.add(violation(sourceFile, method,
                    "@" + own.get().getNameAsString() + " на final методе '" + name + "': прокси не может"
                            + " переопределить такой метод - " + ProxyAnnotations.consequence(own.get())
                            + field.map(found -> ", а поле '" + found + "' в нем окажется пустым: метод выполнится"
                            + " на объекте-прокси, который создается без вызова конструктора").orElse("")
                            + "; уберите final"));
        } else if (field.isPresent()) {
            violations.add(violation(sourceFile, method,
                    "final метод '" + name + "' в классе '" + type.getNameAsString() + "', для которого Spring"
                            + " создает прокси (" + proxyReason + "): CGLIB не может переопределить final метод,"
                            + " и вызов через бин выполнится на самом объекте-прокси, а не на настоящем объекте."
                            + " Прокси создается без вызова конструктора, его поля пусты - обращение к полю '"
                            + field.get() + "' закончится NullPointerException; уберите final"));
        }
    }

    // "@Validated на классе" либо "@Async на методе 'send'"
    private Optional<String> proxyReason(ClassOrInterfaceDeclaration type) {
        Optional<String> onClass = ProxyAnnotations.onClass(type)
                .map(annotation -> "@" + annotation.getNameAsString() + " на классе");
        if (onClass.isPresent()) {
            return onClass;
        }
        // На приватных методах прокси не работает: такая аннотация его и не создает
        return type.getMethods().stream()
                .filter(method -> !method.isPrivate())
                .flatMap(method -> ProxyAnnotations.onMethod(method)
                        .map(annotation -> "@" + annotation.getNameAsString() + " на методе '"
                                + method.getNameAsString() + "'")
                        .stream())
                .findFirst();
    }

    private boolean isTransactional(ClassOrInterfaceDeclaration type) {
        return TransactionalAnnotations.isPresent(type) || type.getMethods().stream()
                .anyMatch(method -> !method.isPrivate() && TransactionalAnnotations.findEffective(method).isPresent());
    }

    /**
     * @return поле объекта, к которому обращается метод - сам либо через свои закрытые и final методы: их прокси
     * тоже не переопределяет, и они выполняются на нем же. Вызов обычного открытого метода уходит настоящему
     * объекту, поэтому дальше него поиск не идет
     */
    private Optional<String> fieldUsedIn(
            MethodDeclaration method, ClassOrInterfaceDeclaration type, int depth, Set<MethodDeclaration> seen) {
        if (!seen.add(method) || method.getBody().isEmpty()) {
            return Optional.empty();
        }
        for (Node node : method.getBody().get().findAll(Node.class)) {
            Optional<String> field = node instanceof NameExpr || node instanceof FieldAccessExpr
                    ? ownField((Expression) node, type)
                    : Optional.empty();
            if (field.isPresent()) {
                return field;
            }
        }
        if (depth == 0) {
            return Optional.empty();
        }
        for (MethodCallExpr call : method.getBody().get().findAll(MethodCallExpr.class)) {
            boolean ownCall = call.getScope().map(Expression::isThisExpr).orElse(true) && enclosingType(call) == type;
            Optional<String> field = ownCall
                    ? MethodCalls.findCallee(call, type.getMethods())
                    .filter(callee -> callee.isPrivate() || callee.isFinal())
                    .flatMap(callee -> fieldUsedIn(callee, type, depth - 1, seen))
                    : Optional.empty();
            if (field.isPresent()) {
                return field;
            }
        }
        return Optional.empty();
    }

    // repository либо this.repository - поле этого класса, а не локальная переменная с тем же именем
    private Optional<String> ownField(Expression expression, ClassOrInterfaceDeclaration type) {
        if (expression.isFieldAccessExpr() && !expression.asFieldAccessExpr().getScope().isThisExpr()) {
            return Optional.empty();
        }
        return LocalTypes.findDeclaration(expression)
                .filter(declaration -> declaration instanceof VariableDeclarator)
                .map(declaration -> (VariableDeclarator) declaration)
                .filter(variable -> variable.getParentNode()
                        .filter(parent -> parent instanceof FieldDeclaration field && isInstanceState(field, variable)
                                && field.getParentNode().filter(owner -> owner == type).isPresent())
                        .isPresent())
                .map(VariableDeclarator::getNameAsString);
    }

    // Статическое поле у прокси то же самое; константу (final с литералом) компилятор подставляет в код сам
    private boolean isInstanceState(FieldDeclaration field, VariableDeclarator variable) {
        boolean constant = field.isFinal() && variable.getInitializer().filter(Expression::isLiteralExpr).isPresent();
        return !field.isStatic() && !constant;
    }

    private Node enclosingType(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null && !(current instanceof TypeDeclaration<?>)) {
            current = current.getParentNode().orElse(null);
        }
        return current;
    }
}
