package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
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
import ru.akvine.zond.rules.support.Repositories;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class ScheduledWithoutLockRule extends AbstractRule {
    private static final Set<String> SCHEDULED_ANNOTATIONS = Set.of("Scheduled", "Schedules");

    // ShedLock: блокировка, общая для всех экземпляров приложения
    private static final String SCHEDULER_LOCK = "SchedulerLock";

    // Защита от повторного входа, написанная вручную
    private static final Set<String> GUARD_METHODS = Set.of("tryLock", "compareAndSet", "tryAcquire");

    // Защита на стороне БД: каждый экземпляр забирает строки, которые не забрал другой, либо берет advisory-замок
    private static final Pattern DATABASE_GUARD =
            Pattern.compile("(?i)skip\\s+locked|for\\s+update|advisory_(xact_)?lock|get_lock\\s*\\(");
    private static final Pattern SQL = Pattern.compile("(?is)^\\s*(select|insert|update|delete|merge|with)\\s.*");
    // Удаление по условию: если его выполнят два экземпляра, второй просто ничего не найдет
    private static final Pattern DELETE_WITH_WHERE = Pattern.compile("(?is)^\\s*delete\\s+from\\s.*\\swhere\\s.*");
    private static final Pattern JDBC = Pattern.compile("(?i).*(jdbc|jdbctemplate|template)$");

    // Ресурсы, общие для всех экземпляров: БД, другие сервисы, очереди, почта
    private static final Pattern SHARED_RESOURCE = Pattern.compile(
            "(?i).*(repository|repo|dao|jdbc|template|entitymanager|session|client|kafka|rabbit|producer|publisher"
                    + "|sender|mail|sms|service|facade|gateway|api|queue)$");
    // Состояние самого экземпляра: кеши и коллекции в его полях, его файловая система
    private static final Set<String> LOCAL_STATE_TYPES = Set.of(
            "Map", "HashMap", "ConcurrentHashMap", "ConcurrentMap", "LinkedHashMap", "Set", "HashSet", "List",
            "ArrayList", "Queue", "Deque", "ConcurrentLinkedQueue", "Cache", "LoadingCache", "AsyncCache");
    private static final Set<String> LOCAL_FILE_CLASSES = Set.of("Files", "FileUtils", "FileSystemUtils");
    private static final String CACHE = "cache";
    // Сколько вызовов своих методов просматривать вглубь от @Scheduled-метода
    private static final int OWN_CALL_DEPTH = 2;
    // Уборка устаревшего: повторная уборка на другом экземпляре ничего не испортит
    private static final Pattern PER_INSTANCE = Pattern.compile("(?i)heartbeat|keep_?alive");
    private static final Pattern EXPIRED_CLEANUP = Pattern.compile(
            "(?i)^(delete|remove|clear|clean|cleanup|purge|evict).*(expired|stale|outdated|obsolete).*");

    @Override
    public String code() {
        return RuleCodes.SCHEDULED_WITHOUT_LOCK_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет @Scheduled-методы без защиты от одновременного запуска";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodDeclaration.class).stream()
                .filter(method -> Annotations.hasAny(method, SCHEDULED_ANNOTATIONS))
                .filter(method -> !Annotations.has(method, SCHEDULER_LOCK))
                .filter(method -> !method.isSynchronized() && !hasManualGuard(method))
                .filter(method -> !isSafeOnEveryInstance(method))
                .map(method -> violation(sourceFile, method,
                        "@Scheduled-метод '" + method.getNameAsString() + "' без контроля одновременного запуска:"
                                + " при нескольких экземплярах приложения задача выполнится на каждом из них,"
                                + " а вместе с @Async - еще и параллельно сама с собой; добавьте распределенную"
                                + " блокировку (например, @SchedulerLock из ShedLock)"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CONCURRENCY;
    }

    private boolean hasManualGuard(MethodDeclaration method) {
        return method.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> GUARD_METHODS.contains(call.getNameAsString()));
    }

    /**
     * Задача, которую можно выполнять на каждом экземпляре: чистит собственный кеш или временные файлы
     * экземпляра, забирает строки через {@code FOR UPDATE SKIP LOCKED} либо одним запросом удаляет устаревшее.
     */
    private boolean isSafeOnEveryInstance(MethodDeclaration method) {
        // deleteExpired, removeStale: второй экземпляр просто не найдет, что удалять
        if (EXPIRED_CLEANUP.matcher(method.getNameAsString()).matches() || isPerInstance(method)) {
            return true;
        }
        List<MethodDeclaration> code = withOwnCallees(method);
        List<MethodCallExpr> calls = code.stream().flatMap(part -> part.findAll(MethodCallExpr.class).stream()).toList();
        List<String> texts = code.stream().flatMap(part -> texts(part).stream()).toList();
        if (texts.stream().anyMatch(text -> DATABASE_GUARD.matcher(text).find())) {
            return true;
        }
        List<MethodCallExpr> shared = calls.stream().filter(this::isSharedCall).toList();
        if (shared.isEmpty()) {
            return calls.stream().anyMatch(this::isLocalStateCall);
        }
        // jdbc.update("delete from tokens where expires_at < ?")
        List<String> statements = texts.stream().filter(text -> SQL.matcher(text).matches()).toList();
        return !statements.isEmpty()
                && statements.stream().allMatch(text -> DELETE_WITH_WHERE.matcher(text).matches())
                && shared.stream().allMatch(call -> JDBC.matcher(receiverOf(call)).matches());
    }

    // Heartbeat и keep-alive каждый экземпляр шлет о себе сам
    private boolean isPerInstance(MethodDeclaration method) {
        String owner = method.findAncestor(ClassOrInterfaceDeclaration.class)
                .map(ClassOrInterfaceDeclaration::getNameAsString)
                .orElse("");
        return PER_INSTANCE.matcher(method.getNameAsString()).find() || PER_INSTANCE.matcher(owner).find();
    }

    // Сам метод и методы своего класса, которые он вызывает
    private List<MethodDeclaration> withOwnCallees(MethodDeclaration method) {
        Set<MethodDeclaration> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(method);
        List<MethodDeclaration> level = List.of(method);
        Optional<ClassOrInterfaceDeclaration> owner = method.findAncestor(ClassOrInterfaceDeclaration.class);
        for (int depth = 0; depth < OWN_CALL_DEPTH && owner.isPresent(); depth++) {
            List<MethodDeclaration> next = new ArrayList<>();
            for (MethodDeclaration current : level) {
                current.findAll(MethodCallExpr.class).stream()
                        .filter(call -> call.getScope().isEmpty() || call.getScope().get().isThisExpr())
                        .flatMap(call -> owner.get().getMethodsByName(call.getNameAsString()).stream())
                        .filter(seen::add)
                        .forEach(next::add);
            }
            level = next;
        }
        return new ArrayList<>(seen);
    }

    // Строки метода: литералы и значения констант, на которые он ссылается
    private List<String> texts(MethodDeclaration method) {
        List<String> texts = new ArrayList<>();
        method.findAll(StringLiteralExpr.class).forEach(literal -> texts.add(literal.getValue()));
        method.findAll(TextBlockLiteralExpr.class).forEach(literal -> texts.add(literal.getValue()));
        for (NameExpr name : method.findAll(NameExpr.class)) {
            LocalTypes.findField(name, name.getNameAsString())
                    .flatMap(VariableDeclarator::getInitializer)
                    .ifPresent(initializer -> {
                        if (initializer.isStringLiteralExpr()) {
                            texts.add(initializer.asStringLiteralExpr().getValue());
                        } else if (initializer.isTextBlockLiteralExpr()) {
                            texts.add(initializer.asTextBlockLiteralExpr().getValue());
                        }
                    });
        }
        return texts;
    }

    private boolean isSharedCall(MethodCallExpr call) {
        return Repositories.isRepositoryCall(call) || SHARED_RESOURCE.matcher(receiverOf(call)).matches()
                || call.getScope().flatMap(LocalTypes::typeOf).filter(type -> SHARED_RESOURCE.matcher(type).matches())
                .isPresent();
    }

    // Files.deleteIfExists(...), blockedCache.entrySet().removeIf(...)
    private boolean isLocalStateCall(MethodCallExpr call) {
        Optional<Expression> root = root(call);
        if (root.isEmpty()) {
            return false;
        }
        String name = MethodCalls.receiverName(root.get());
        if (LOCAL_FILE_CLASSES.contains(name)) {
            return true;
        }
        Optional<VariableDeclarator> field = root.get().isNameExpr()
                ? LocalTypes.findField(root.get(), name)
                : Optional.empty();
        return field.isPresent() && LocalTypes.findDeclaration(root.get()).filter(found -> found == field.get()).isPresent()
                && (LOCAL_STATE_TYPES.contains(LocalTypes.typeName(field.get().getType()))
                || name.toLowerCase().endsWith(CACHE));
    }

    // Начало цепочки вызовов: blockedCache.entrySet().removeIf(...) -> blockedCache
    private Optional<Expression> root(MethodCallExpr call) {
        Optional<Expression> scope = call.getScope();
        while (scope.isPresent() && scope.get().isMethodCallExpr()) {
            scope = scope.get().asMethodCallExpr().getScope();
        }
        return scope;
    }

    private String receiverOf(MethodCallExpr call) {
        return call.getScope().map(MethodCalls::receiverName).orElse("");
    }
}
