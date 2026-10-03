package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import lombok.experimental.UtilityClass;

import java.util.Optional;
import java.util.Set;

/**
 * Какая коллекция лежит в переменной: по инициализатору (Map<K, V> cache = new ConcurrentHashMap<>())
 * либо по объявленному типу.
 */
@UtilityClass
class CollectionKinds {
    private final static String COLLECTIONS = "Collections";
    private final static String SYNCHRONIZED_PREFIX = "synchronized";

    private final static Set<String> CONCURRENT_MAP_TYPES =
            Set.of("ConcurrentHashMap", "ConcurrentMap", "ConcurrentSkipListMap", "ConcurrentNavigableMap");

    boolean isConcurrentMap(Expression scope) {
        return LocalTypes.findDeclaration(scope)
                .flatMap(CollectionKinds::implementation)
                .filter(CONCURRENT_MAP_TYPES::contains)
                .isPresent();
    }

    /**
     * @return true, если коллекция из выражения рассчитана на работу из нескольких потоков
     */
    boolean isThreadSafe(Expression scope) {
        return LocalTypes.findDeclaration(scope).filter(CollectionKinds::isThreadSafeDeclaration).isPresent();
    }

    /**
     * @return true для Concurrent*, CopyOnWrite*, блокирующих очередей и Collections.synchronized*
     */
    boolean isThreadSafeDeclaration(Node declaration) {
        boolean isSynchronizedWrapper = initializer(declaration)
                .filter(Expression::isMethodCallExpr)
                .map(Expression::asMethodCallExpr)
                .filter(call -> call.getNameAsString().startsWith(SYNCHRONIZED_PREFIX)
                        && call.getScope().filter(type -> MethodCalls.isType(type, COLLECTIONS)).isPresent())
                .isPresent();
        return isSynchronizedWrapper || implementation(declaration)
                .filter(type -> type.startsWith("Concurrent") || type.startsWith("CopyOnWrite")
                        || type.endsWith("BlockingQueue") || type.endsWith("BlockingDeque"))
                .isPresent();
    }

    // Класс из new ...(), а если инициализатора нет - объявленный тип
    private Optional<String> implementation(Node declaration) {
        Optional<String> created = initializer(declaration)
                .filter(Expression::isObjectCreationExpr)
                .map(value -> value.asObjectCreationExpr().getType().getNameAsString());
        return created.or(() -> LocalTypes.declaredType(declaration));
    }

    private Optional<Expression> initializer(Node declaration) {
        return Optional.of(declaration)
                .filter(node -> node instanceof VariableDeclarator)
                .flatMap(node -> ((VariableDeclarator) node).getInitializer())
                .map(Nodes::unwrap);
    }
}
