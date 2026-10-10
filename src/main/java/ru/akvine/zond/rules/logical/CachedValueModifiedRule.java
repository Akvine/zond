package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CacheAnnotations;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ProjectWords;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Объект, полученный из кеша, меняют на месте. Кеш в памяти хранит ссылку, а не копию: изменение попадает
 * в кеш и достается всем, кто получит объект следующим.
 */
@Component
public class CachedValueModifiedRule extends AbstractContextRule {
    private static final Set<String> MUTATORS = Set.of(
            "add", "addAll", "addFirst", "addLast", "remove", "removeAll", "removeIf", "retainAll", "clear", "sort",
            "put", "putAll", "putIfAbsent", "replaceAll", "set", "push", "pop");
    private static final Pattern SETTER = Pattern.compile("^set[A-Z].*");
    // Collections.sort(list), Collections.reverse(list)
    private static final String COLLECTIONS = "Collections";
    private static final Set<String> COLLECTIONS_MUTATORS = Set.of("sort", "reverse", "shuffle", "swap", "fill", "addAll");

    // Кеш вне памяти приложения хранит сериализованную копию и каждый раз отдает новый объект
    private static final Set<String> SERIALIZING_CACHE = Set.of(
            "RedisCacheManager", "RedisCacheConfiguration", "HazelcastCacheManager", "CouchbaseCacheManager");
    private static final String CACHE_TYPE = "spring.cache.type";
    private static final Set<String> REMOTE_TYPES = Set.of("redis", "hazelcast", "couchbase", "infinispan");

    @Override
    public String code() {
        return RuleCodes.CACHED_VALUE_MODIFIED_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет изменение объекта, полученного из @Cacheable-метода";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        boolean remote = context.configFiles().stream()
                .anyMatch(file -> file.find(CACHE_TYPE)
                        .filter(type -> REMOTE_TYPES.contains(type.value().trim().toLowerCase()))
                        .isPresent());
        if (remote || ProjectWords.hasAny(context.sources(), SERIALIZING_CACHE)) {
            return violations;
        }
        CallGraph graph = CallGraph.of(context.sources());
        for (SourceFile sourceFile : context.sources()) {
            for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
                Optional<MethodDeclaration> cached = graph.targetsOf(call).stream()
                        .filter(target -> CacheAnnotations.has(target, Set.of(CacheAnnotations.CACHEABLE)))
                        .findFirst();
                if (cached.isEmpty()) {
                    continue;
                }
                for (MethodCallExpr change : changesOf(call)) {
                    violations.add(violation(sourceFile, change,
                            "Объект из кеша ('" + cached.get().getNameAsString() + "' помечен @Cacheable) меняется"
                                    + " вызовом '" + change.getNameAsString() + "': кеш в памяти отдает всем один"
                                    + " и тот же объект, поэтому изменение увидят и остальные, кто получит его"
                                    + " из кеша; работайте с копией либо возвращайте из кешируемого метода"
                                    + " неизменяемый объект"));
                }
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    // service.cached().add(x) либо List<X> list = service.cached(); ... list.add(x)
    private List<MethodCallExpr> changesOf(MethodCallExpr cachedCall) {
        Optional<Node> parent = cachedCall.getParentNode();
        if (parent.filter(node -> node instanceof MethodCallExpr outer
                && outer.getScope().filter(scope -> scope == cachedCall).isPresent()
                && isMutator(outer)).isPresent()) {
            return List.of((MethodCallExpr) parent.get());
        }
        if (parent.isEmpty() || !(parent.get() instanceof VariableDeclarator variable)) {
            return List.of();
        }
        Optional<Node> callable = Nodes.enclosingCallable(cachedCall);
        if (callable.isEmpty()) {
            return List.of();
        }
        String name = variable.getNameAsString();
        // Переменной позже присвоили другое значение (копию): дальше меняют уже не объект из кеша
        boolean reassigned = callable.get().findAll(AssignExpr.class).stream()
                .anyMatch(assignment -> assignment.getTarget().toString().equals(name));
        if (reassigned) {
            return List.of();
        }
        return callable.get().findAll(MethodCallExpr.class).stream()
                .filter(call -> isAfter(call, variable))
                .filter(call -> changes(call, name))
                .toList();
    }

    private boolean changes(MethodCallExpr call, String name) {
        boolean onVariable = call.getScope().filter(scope -> scope.toString().equals(name)).isPresent();
        if (onVariable) {
            return isMutator(call);
        }
        return COLLECTIONS_MUTATORS.contains(call.getNameAsString())
                && call.getScope().filter(scope -> scope.toString().equals(COLLECTIONS)).isPresent()
                && !call.getArguments().isEmpty()
                && isName(call.getArgument(0), name);
    }

    private boolean isMutator(MethodCallExpr call) {
        return MUTATORS.contains(call.getNameAsString()) && !call.getArguments().isEmpty()
                || "clear".equals(call.getNameAsString())
                || SETTER.matcher(call.getNameAsString()).matches() && call.getArguments().size() == 1;
    }

    private boolean isName(Expression expression, String name) {
        return expression.isNameExpr() && expression.asNameExpr().getNameAsString().equals(name);
    }

    private boolean isAfter(Node node, Node other) {
        return node.getBegin().isPresent() && other.getEnd().isPresent()
                && node.getBegin().get().isAfter(other.getEnd().get());
    }
}
