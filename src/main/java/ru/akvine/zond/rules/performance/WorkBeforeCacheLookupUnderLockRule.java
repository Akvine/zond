package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.TestPaths;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ThreadReach;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Метод "взять из кеша или создать" целиком под блокировкой, и до проверки кеша он вызывает другой сервис:
 * расшифровку, запрос, разбор. Этот вызов выполняется на каждое обращение и под блокировкой - его ждут все
 * потоки, даже когда значение давно лежит в кеше. Вызов выносят из-под блокировки либо выполняют только
 * при промахе.
 */
@Component
public class WorkBeforeCacheLookupUnderLockRule extends AbstractRule implements ProjectRule {
    // containsKey и computeIfAbsent бывают только у карт и кешей; get есть у чего угодно - для него смотрим на тип поля
    private static final Set<String> MAP_LOOKUPS = Set.of("containsKey", "getIfPresent", "computeIfAbsent");
    private static final String GET = "get";
    private static final Set<String> STORES = Set.of("put", "putIfAbsent", "computeIfAbsent");
    private static final Pattern CACHE_TYPE = Pattern.compile(".*(Map|Cache|Store|Registry)$");

    // Вызовы на таких полях - не работа другого сервиса: журнал, счетчики, коллекции, блокировки
    private static final Pattern PLAIN_TYPE = Pattern.compile(
            ".*(Map|Set|List|Queue|Deque|Collection|Cache|Logger|Log|Lock|Condition|Semaphore|Latch|Clock|Random"
                    + "|Counter|Timer|Gauge|Meter|Properties|Config|Configuration|Settings)$"
                    + "|Atomic\\w+|String|StringBuilder|Object|Optional|Duration|Instant|Thread|Class|\\w+Properties");
    private static final Pattern LOGGER_NAME = Pattern.compile("log|logger|LOG|LOGGER");

    /**
     * @param call  вызов другого сервиса до проверки кеша
     * @param cache поле-кеш
     */
    private record Found(MethodCallExpr call, String cache) {
    }

    @Override
    public String code() {
        return RuleCodes.WORK_BEFORE_CACHE_LOOKUP_UNDER_LOCK_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет вызов другого сервиса под synchronized до проверки кеша: его ждут все потоки,"
                + " даже когда значение уже есть в кеше";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ThreadReach reach = null;
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            if (TestPaths.isTest(sourceFile.path())) {
                continue;
            }
            List<NodeList<Statement>> regions = new ArrayList<>();
            for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
                if (method.isSynchronized() && !method.isStatic()) {
                    method.getBody().ifPresent(body -> regions.add(body.getStatements()));
                }
            }
            for (SynchronizedStmt block : sourceFile.unit().findAll(SynchronizedStmt.class)) {
                regions.add(block.getBody().getStatements());
            }
            for (NodeList<Statement> region : regions) {
                Optional<Found> found = find(region);
                if (found.isEmpty()) {
                    continue;
                }
                if (reach == null) {
                    reach = ThreadReach.of(sourceFiles);
                }
                // Код, до которого доходит один поток, блокировку ни с кем не делит
                if (reach.of(found.get().call()) == ThreadReach.Reach.SINGLE) {
                    continue;
                }
                MethodCallExpr call = found.get().call();
                violations.add(violation(sourceFile, call,
                        "'" + call.getScope().get() + "." + call.getNameAsString() + "(...)' выполняется под"
                                + " блокировкой до проверки кеша '" + found.get().cache() + "': этот вызов ждут"
                                + " все потоки, даже когда значение уже в кеше; вынесите его из-под блокировки"
                                + " либо выполняйте только при промахе"));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    private Optional<Found> find(NodeList<Statement> region) {
        for (int index = 0; index < region.size(); index++) {
            Optional<String> cache = findLookup(region.get(index));
            if (cache.isEmpty()) {
                continue;
            }
            // Проверка кеша - первое, что делается под блокировкой
            if (index == 0) {
                return Optional.empty();
            }
            // Кеш, который здесь же и пополняется: "взять или создать", а не просто чтение карты
            String name = cache.get();
            boolean isFilledHere = region.stream()
                    .flatMap(statement -> statement.findAll(MethodCallExpr.class).stream())
                    .anyMatch(call -> STORES.contains(call.getNameAsString()) && isCallOnField(call, name));
            if (!isFilledHere) {
                return Optional.empty();
            }
            for (int before = 0; before < index; before++) {
                for (MethodCallExpr call : region.get(before).findAll(MethodCallExpr.class)) {
                    if (isCollaboratorCall(call, name)) {
                        return Optional.of(new Found(call, name));
                    }
                }
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    // if (!pools.containsKey(key)), Pool pool = pools.get(key), return pools.computeIfAbsent(key, ...)
    private Optional<String> findLookup(Statement statement) {
        for (MethodCallExpr call : statement.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (!MAP_LOOKUPS.contains(name) && !GET.equals(name) || call.getScope().isEmpty()) {
                continue;
            }
            Optional<String> field = fieldName(call.getScope().get());
            Optional<String> type = field.flatMap(found -> fieldType(call, found));
            if (type.isEmpty()) {
                continue;
            }
            boolean isCache = MAP_LOOKUPS.contains(name)
                    ? !PLAIN_TYPE.matcher(type.get()).matches() || CACHE_TYPE.matcher(type.get()).matches()
                    : call.getArguments().size() == 1 && CACHE_TYPE.matcher(type.get()).matches();
            if (isCache) {
                return field;
            }
        }
        return Optional.empty();
    }

    private boolean isCallOnField(MethodCallExpr call, String field) {
        return call.getScope().flatMap(this::fieldName).filter(field::equals).isPresent();
    }

    // Вызов метода у поля-сервиса: encryptorService.decrypt(...), а не log.debug(...) и не counters.increment()
    private boolean isCollaboratorCall(MethodCallExpr call, String cache) {
        if (call.getScope().isEmpty()) {
            return false;
        }
        Optional<String> field = fieldName(call.getScope().get());
        if (field.isEmpty() || field.get().equals(cache) || LOGGER_NAME.matcher(field.get()).matches()) {
            return false;
        }
        return fieldType(call, field.get()).filter(type -> !PLAIN_TYPE.matcher(type).matches()).isPresent();
    }

    // Имя поля, на котором вызван метод: pools или this.pools; локальная переменная с тем же именем - не поле
    private Optional<String> fieldName(Expression scope) {
        Expression value = Nodes.unwrap(scope);
        if (value.isFieldAccessExpr() && value.asFieldAccessExpr().getScope().isThisExpr()) {
            return Optional.of(value.asFieldAccessExpr().getNameAsString());
        }
        if (value.isNameExpr() && !LocalTypes.isLocal(value, value.asNameExpr().getNameAsString())) {
            return Optional.of(value.asNameExpr().getNameAsString());
        }
        return Optional.empty();
    }

    private Optional<String> fieldType(Node from, String field) {
        return LocalTypes.findField(from, field)
                .map(VariableDeclarator::getType)
                .map(LocalTypes::typeName);
    }
}
