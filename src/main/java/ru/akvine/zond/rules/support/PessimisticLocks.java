package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Пессимистическая блокировка строки в базе: {@code @Lock(PESSIMISTIC_WRITE)} и запрос FOR UPDATE на методе
 * репозитория, {@code entityManager.lock(...)} / {@code find(...)} с LockModeType, {@code query.setLockMode(...)}.
 * Такой запрос ждет, пока строку держит другая транзакция, и ожидание может закончиться исключением.
 */
public final class PessimisticLocks {
    private static final String LOCK = "Lock";
    private static final String QUERY = "Query";
    private static final Pattern MODE = Pattern.compile("PESSIMISTIC_[A-Z_]+");
    // select ... for update, for no key update, for share
    private static final Pattern FOR_UPDATE = Pattern.compile(
            "\\bfor\\s+(no\\s+key\\s+)?(update|share)\\b", Pattern.CASE_INSENSITIVE);
    // Занятые строки пропускаются: такой запрос не ждет и исключением не заканчивается.
    // jakarta.persistence.lock.timeout = -2 - то же самое подсказкой JPA
    private static final Pattern SKIP_LOCKED = Pattern.compile(
            "skip\\s+locked|SKIP_LOCKED|UPGRADE_SKIPLOCKED|lock\\.timeout\"\\s*,\\s*(value\\s*=\\s*)?\"?-2",
            Pattern.CASE_INSENSITIVE);
    // entityManager.find(Order.class, id, PESSIMISTIC_WRITE), lock(order, ...), refresh(order, ...), query.setLockMode(...)
    private static final Set<String> ENTITY_MANAGER_METHODS = Set.of("find", "lock", "refresh", "setLockMode");
    // interface OrderRepository extends BaseRepository<Order>, а тот - JpaRepository<T, Long>: глубже не бывает
    private static final int MAX_DEPTH = 3;

    private final ProjectClasses classes;
    // Имена методов репозиториев с блокировкой: остальные вызовы проекта разбирать незачем
    private final Set<String> lockingNames = new HashSet<>();

    private PessimisticLocks(ProjectClasses classes) {
        this.classes = classes;
        for (ClassOrInterfaceDeclaration type : classes.all()) {
            if (type.isInterface()) {
                type.getMethods().stream()
                        .filter(method -> describe(method).isPresent())
                        .forEach(method -> lockingNames.add(method.getNameAsString()));
            }
        }
    }

    public static PessimisticLocks of(ProjectClasses classes) {
        return new PessimisticLocks(classes);
    }

    /**
     * @return чем вызов берет блокировку строки (например, "@Lock(PESSIMISTIC_WRITE) на
     * 'AccountRepository.findByIdForUpdate'"); пусто, если блокировки нет либо занятые строки пропускаются
     */
    public Optional<String> describe(MethodCallExpr call) {
        String name = call.getNameAsString();
        if (ENTITY_MANAGER_METHODS.contains(name)) {
            Optional<String> mode = call.getArguments().stream()
                    .map(argument -> MODE.matcher(argument.toString()))
                    .filter(Matcher::find)
                    .map(Matcher::group)
                    .findFirst();
            if (mode.isPresent()) {
                // Подсказку с таймаутом задают в той же цепочке либо в том же вызове
                String statement = call.findAncestor(Statement.class)
                        .map(Object::toString)
                        .orElseGet(call::toString);
                return SKIP_LOCKED.matcher(statement).find()
                        ? Optional.empty()
                        : Optional.of(call.getScope().map(scope -> MethodCalls.receiverName(scope) + ".").orElse("")
                        + name + "(..., " + mode.get() + ")");
            }
        }
        if (!lockingNames.contains(name) || call.getScope().isEmpty()) {
            return Optional.empty();
        }
        return RepositoryEntities.repositoryOf(call.getScope().get(), classes)
                .flatMap(repository -> declared(repository, call, MAX_DEPTH))
                .flatMap(method -> describe(method).map(lock -> lock + " на '" + ownerName(method) + "."
                        + method.getNameAsString() + "'"));
    }

    // Метод объявлен в самом репозитории либо в его базовом интерфейсе из проекта
    private Optional<MethodDeclaration> declared(ClassOrInterfaceDeclaration repository, MethodCallExpr call, int depth) {
        Optional<MethodDeclaration> own = repository.getMethodsByName(call.getNameAsString()).stream()
                .filter(method -> method.getParameters().size() == call.getArguments().size())
                .findFirst();
        if (own.isPresent() || depth == 0) {
            return own;
        }
        for (ClassOrInterfaceType parent : repository.getExtendedTypes()) {
            Optional<MethodDeclaration> inherited = classes.resolve(parent)
                    .filter(ClassOrInterfaceDeclaration::isInterface)
                    .flatMap(base -> declared(base, call, depth - 1));
            if (inherited.isPresent()) {
                return inherited;
            }
        }
        return Optional.empty();
    }

    // "@Lock(PESSIMISTIC_WRITE)" либо "запрос FOR UPDATE"
    private Optional<String> describe(MethodDeclaration method) {
        String annotations = method.getAnnotations().stream().map(AnnotationExpr::toString).collect(Collectors.joining(" "));
        if (SKIP_LOCKED.matcher(annotations).find()) {
            return Optional.empty();
        }
        Optional<String> mode = Annotations.find(method, LOCK)
                .map(annotation -> MODE.matcher(annotation.toString()))
                .filter(Matcher::find)
                .map(Matcher::group);
        if (mode.isPresent()) {
            return Optional.of("@Lock(" + mode.get() + ")");
        }
        return Annotations.find(method, QUERY)
                .filter(query -> FOR_UPDATE.matcher(query.toString()).find())
                .map(query -> "запрос FOR UPDATE");
    }

    private String ownerName(MethodDeclaration method) {
        return method.findAncestor(ClassOrInterfaceDeclaration.class)
                .map(ClassOrInterfaceDeclaration::getNameAsString)
                .orElse("");
    }
}
