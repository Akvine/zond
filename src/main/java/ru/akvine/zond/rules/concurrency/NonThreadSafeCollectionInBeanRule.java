package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.SpringBeans;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class NonThreadSafeCollectionInBeanRule extends AbstractRule {
    private static final String POST_CONSTRUCT = "PostConstruct";

    private static final Set<String> NOT_THREAD_SAFE_TYPES = Set.of(
            "HashMap", "LinkedHashMap", "TreeMap", "ArrayList", "LinkedList", "HashSet", "LinkedHashSet", "TreeSet",
            "ArrayDeque", "PriorityQueue");

    private static final Set<String> MUTATING_METHODS = Set.of(
            "put", "putAll", "putIfAbsent", "computeIfAbsent", "compute", "merge", "add", "addAll", "remove",
            "removeAll", "removeIf", "clear", "push", "pop", "poll", "offer");

    @Override
    public String code() {
        return RuleCodes.NON_THREAD_SAFE_COLLECTION_IN_BEAN_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет в Spring-бинах обычные коллекции (HashMap, ArrayList), которые меняются в методах";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration bean : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (!SpringBeans.isBean(bean)) {
                continue;
            }
            for (FieldDeclaration field : bean.getFields()) {
                for (VariableDeclarator variable : field.getVariables()) {
                    if (!isNotThreadSafe(variable)) {
                        continue;
                    }
                    Set<String> writers = findWriters(bean, variable.getNameAsString());
                    if (!writers.isEmpty()) {
                        violations.add(violation(sourceFile, variable,
                                "Коллекция '" + variable.getNameAsString() + "' в бине '" + bean.getNameAsString()
                                        + "' не потокобезопасна, а меняется в методе " + String.join(", ", writers)
                                        + ": бин один на все потоки, одновременная запись портит коллекцию вплоть до"
                                        + " зависания HashMap; используйте ConcurrentHashMap / CopyOnWriteArrayList"));
                    }
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
        return ErrorType.CONCURRENCY;
    }

    // По инициализатору (Map<K, V> cache = new HashMap<>()) либо по объявленному типу
    private boolean isNotThreadSafe(VariableDeclarator variable) {
        String type = variable.getInitializer()
                .map(Nodes::unwrap)
                .filter(initializer -> initializer.isObjectCreationExpr())
                .map(initializer -> initializer.asObjectCreationExpr().getType().getNameAsString())
                .orElseGet(() -> LocalTypes.typeName(variable.getType()));
        return NOT_THREAD_SAFE_TYPES.contains(type);
    }

    // Методы, которые меняют коллекцию без синхронизации. @PostConstruct выполняется один раз до начала работы
    private Set<String> findWriters(ClassOrInterfaceDeclaration bean, String collection) {
        Set<String> writers = new LinkedHashSet<>();
        for (MethodDeclaration method : bean.getMethods()) {
            if (method.isSynchronized() || Annotations.has(method, POST_CONSTRUCT)) {
                continue;
            }
            boolean writes = method.findAll(MethodCallExpr.class).stream()
                    .filter(call -> MUTATING_METHODS.contains(call.getNameAsString()))
                    .filter(call -> call.getScope()
                            .filter(scope -> MethodCalls.receiverName(scope).equals(collection))
                            .isPresent())
                    .anyMatch(call -> !isInsideSynchronized(call, method));
            if (writes) {
                writers.add("'" + method.getNameAsString() + "'");
            }
        }
        return writers;
    }

    private boolean isInsideSynchronized(Node node, MethodDeclaration method) {
        Node current = node.getParentNode().orElse(null);
        while (current != null && current != method) {
            if (current instanceof SynchronizedStmt) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }
}
