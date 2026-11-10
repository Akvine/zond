package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import ru.akvine.zond.models.SourceFile;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Кто отвечает за отказ операции с базой: try-catch вокруг нее, повтор (@Retryable, RetryTemplate) на методе
 * или у вызывающих, обработчик исключения на все приложение (@ExceptionHandler).
 * <p>
 * Общая часть правил о блокировках и версиях: отказ, которого никто из них не ждет, уходит из точки входа
 * как есть и заканчивается ошибкой 500.
 */
public final class FailureHandling {
    /**
     * Отказ пессимистической блокировки и ошибка сериализации: JPA, Hibernate, Spring
     */
    public static final Set<String> LOCK_FAILURES = Set.of(
            "PessimisticLockException", "LockTimeoutException", "PessimisticLockingFailureException",
            "CannotAcquireLockException", "CannotSerializeTransactionException", "DeadlockLoserDataAccessException",
            "ConcurrencyFailureException", "TransientDataAccessException", "QueryTimeoutException",
            "LockAcquisitionException", "PessimisticEntityLockException");

    /**
     * Отказ оптимистической блокировки: запись изменил другой запрос
     */
    public static final Set<String> OPTIMISTIC_FAILURES = Set.of(
            "OptimisticLockException", "OptimisticLockingFailureException",
            "ObjectOptimisticLockingFailureException", "StaleObjectStateException", "StaleStateException",
            "OptimisticEntityLockException", "ConcurrencyFailureException", "TransientDataAccessException");

    private static final String EXCEPTION_HANDLER = "ExceptionHandler";
    private static final String EXCEPTION_MAPPER = "ExceptionMapper";

    // Предки исключений базы: catch с ними рядом с операцией - тоже ответ на ее отказ. Обработчик на все
    // приложение для такого предка ответом не считается: он отдает ту же ошибку 500
    private static final Set<String> GENERAL_FAILURES = Set.of(
            "DataAccessException", "PersistenceException", "JpaSystemException", "HibernateException", "SQLException",
            "TransactionException", "TransactionSystemException", "Exception", "RuntimeException", "Throwable");

    // Spring Retry и Spring Framework 7 (@Retryable), Resilience4j (@Retry)
    private static final Set<String> RETRY_ANNOTATIONS = Set.of("Retryable", "Retry");
    // retryTemplate.execute(...), Retry.decorateSupplier(retry, ...), withRetry(() -> ...)
    private static final Pattern RETRY_NAME = Pattern.compile(".*retr(y|ie[sd]).*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    // Задачу по расписанию запустят снова в следующий раз
    private static final Set<String> SCHEDULED = Set.of("Scheduled", "Schedules");

    private final CallGraph graph;
    private final Set<String> answered = new HashSet<>();

    private FailureHandling(List<SourceFile> sources) {
        graph = CallGraph.of(sources);
        for (SourceFile source : sources) {
            source.unit().findAll(AnnotationExpr.class).stream()
                    .filter(annotation -> EXCEPTION_HANDLER.equals(annotation.getName().getIdentifier()))
                    .forEach(annotation -> answered.addAll(handledBy(annotation)));
            // class LockMapper implements ExceptionMapper<PessimisticLockException> - обработчик JAX-RS
            source.unit().findAll(ClassOrInterfaceType.class).stream()
                    .filter(type -> EXCEPTION_MAPPER.equals(type.getNameAsString()))
                    .flatMap(type -> type.getTypeArguments().stream().flatMap(List::stream))
                    .forEach(argument -> answered.add(LocalTypes.typeName(argument)));
        }
    }

    public static FailureHandling of(List<SourceFile> sources) {
        return new FailureHandling(sources);
    }

    /**
     * @return true, если в проекте есть обработчик (@ExceptionHandler, ExceptionMapper) для одного из исключений:
     * клиент получит осмысленный ответ, где бы оно ни возникло
     */
    public boolean hasGlobalHandler(Set<String> failures) {
        return !Collections.disjoint(answered, failures);
    }

    /**
     * Уходит ли отказ операции из приложения необработанным.
     *
     * @param site     место, где отказ возникает: вызов с блокировкой, сохранение сущности
     * @param failures исключения, которыми об отказе сообщают
     * @param depth    на сколько вызовов вверх искать того, кто отказ перехватит или повторит операцию
     * @return true, если есть путь от точки входа до операции, на котором отказ никто не перехватывает
     * и операцию не повторяет. Цепочка длиннее {@code depth} считается неизвестной: о ней правило молчит
     */
    public boolean escapes(Node site, Set<String> failures, int depth) {
        return escapes(site, failures, depth, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * То же для отказа, который возникает на выходе из метода: при коммите его транзакции
     */
    public boolean escapesFrom(MethodDeclaration method, Set<String> failures, int depth) {
        return escapesFrom(method, failures, depth, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private boolean escapes(Node site, Set<String> failures, int depth, Set<MethodDeclaration> visited) {
        Optional<MethodDeclaration> method = uncaughtIn(site, failures);
        return method.isPresent() && escapesFrom(method.get(), failures, depth, visited);
    }

    private boolean escapesFrom(MethodDeclaration method, Set<String> failures, int depth, Set<MethodDeclaration> visited) {
        if (isRetried(method) || isRepeatedByContainer(method)) {
            return false;
        }
        // Тесты в рабочей среде не выполняются: вызов из теста точкой входа метод не делает и отказ не обрабатывает
        List<CallGraph.Call> callers = graph.callsTo(method).stream()
                .filter(call -> call.caller() != method && !TestClasses.isInside(call.site()))
                .toList();
        if (callers.isEmpty()) {
            // Точка входа: метод вызывает контейнер, и исключение уходит клиенту
            return true;
        }
        if (depth <= 0 || !visited.add(method)) {
            return false;
        }
        return callers.stream().anyMatch(call -> escapes(call.site(), failures, depth - 1, visited));
    }

    /**
     * @return метод, из которого отказ уходит дальше; пусто, если по дороге его ловит try-catch либо операцию
     * повторяет обертка (retryTemplate.execute), а также для кода вне методов: конструктора, инициализатора
     */
    private Optional<MethodDeclaration> uncaughtIn(Node site, Set<String> failures) {
        Node child = site;
        Node current = site.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof TryStmt statement && isProtected(statement, child)
                    && statement.getCatchClauses().stream().anyMatch(clause -> catches(clause, failures))) {
                return Optional.empty();
            }
            if (current instanceof MethodCallExpr wrapper && isRetryWrapper(wrapper, child)) {
                return Optional.empty();
            }
            if (current instanceof ConstructorDeclaration || current instanceof InitializerDeclaration) {
                return Optional.empty();
            }
            // Метод анонимного класса (new TransactionCallback<>() {...}) выполняется там же, где класс создан
            if (current instanceof MethodDeclaration method
                    && method.getParentNode().filter(parent -> parent instanceof ObjectCreationExpr).isEmpty()) {
                return Optional.of(method);
            }
            child = current;
            current = current.getParentNode().orElse(null);
        }
        return Optional.empty();
    }

    // Исключение из catch и finally этот же try уже не ловит
    private boolean isProtected(TryStmt statement, Node child) {
        return statement.getTryBlock() == child || statement.getResources().stream().anyMatch(resource -> resource == child);
    }

    private boolean catches(CatchClause clause, Set<String> failures) {
        Type type = clause.getParameter().getType();
        List<? extends Type> types = type.isUnionType() ? type.asUnionType().getElements() : List.of(type);
        return types.stream()
                .map(LocalTypes::typeName)
                .anyMatch(name -> failures.contains(name) || GENERAL_FAILURES.contains(name));
    }

    // Код передан аргументом в то, что выполнит его с повтором
    private boolean isRetryWrapper(MethodCallExpr wrapper, Node child) {
        boolean argument = wrapper.getArguments().stream().anyMatch(candidate -> candidate == child);
        return argument && (RETRY_NAME.matcher(wrapper.getNameAsString()).matches()
                || wrapper.getScope().filter(scope -> RETRY_NAME.matcher(scope.toString()).matches()).isPresent());
    }

    // @Retryable на методе либо на его классе
    private boolean isRetried(MethodDeclaration method) {
        return Annotations.hasAny(method, RETRY_ANNOTATIONS)
                || method.findAncestor(ClassOrInterfaceDeclaration.class)
                .filter(owner -> Annotations.hasAny(owner, RETRY_ANNOTATIONS))
                .isPresent();
    }

    // Сообщение из очереди брокер после ошибки доставит снова, задача по расписанию выполнится в следующий раз
    private boolean isRepeatedByContainer(MethodDeclaration method) {
        return Messaging.isListener(method) || Annotations.hasAny(method, SCHEDULED);
    }

    // @ExceptionHandler(LockTimeoutException.class) либо @ExceptionHandler на методе с таким параметром
    private Set<String> handledBy(AnnotationExpr annotation) {
        Set<String> exceptions = new HashSet<>();
        annotation.findAll(ClassExpr.class).forEach(type -> exceptions.add(LocalTypes.typeName(type.getType())));
        if (exceptions.isEmpty()) {
            annotation.getParentNode()
                    .filter(parent -> parent instanceof MethodDeclaration)
                    .map(parent -> (MethodDeclaration) parent)
                    .ifPresent(method -> method.getParameters()
                            .forEach(parameter -> exceptions.add(LocalTypes.typeName(parameter.getType()))));
        }
        return exceptions;
    }
}
