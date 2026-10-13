package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CollectionKinds;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code map.containsKey(key)} и следом {@code map.get(key)} или {@code map.put(key, ...)} с тем же ключом.
 * Ключ ищется в карте дважды: хеш считается два раза, а в {@code TreeMap} дважды проходится дерево.
 * У карты есть операции, которые делают то же одним обращением: {@code get} с проверкой на null,
 * {@code getOrDefault}, {@code putIfAbsent}, {@code computeIfAbsent}, {@code merge}.
 * <p>
 * О конкурентных картах говорит jr:172: там пара отдельных вызовов - уже не потеря времени, а гонка.
 */
@Component
public class MapDoubleLookupRule extends AbstractRule {
    private static final String CONTAINS_KEY = "containsKey";
    private static final String GET = "get";
    private static final String PUT = "put";
    private static final String THIS_PREFIX = "this.";
    private static final Set<String> MAP_TYPES = Set.of(
            "Map", "HashMap", "LinkedHashMap", "TreeMap", "SortedMap", "NavigableMap", "EnumMap", "IdentityHashMap",
            "WeakHashMap", "Hashtable");

    @Override
    public String code() {
        return RuleCodes.MAP_DOUBLE_LOOKUP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет двойной поиск в Map: containsKey() и следом get() или put() с тем же ключом";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr check : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!CONTAINS_KEY.equals(check.getNameAsString()) || check.getArguments().size() != 1
                    || check.getScope().isEmpty() || TestClasses.isInside(check) || !isPlainMap(check.getScope().get())) {
                continue;
            }
            List<MethodCallExpr> repeats = repeats(check);
            if (!repeats.isEmpty()) {
                violations.add(violation(sourceFile, check.getName(), message(check, repeats)));
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

    private boolean isPlainMap(Expression map) {
        return LocalTypes.findDeclaration(map).flatMap(LocalTypes::declaredType).filter(MAP_TYPES::contains).isPresent()
                && !CollectionKinds.isConcurrentMap(map);
    }

    /**
     * Обращения к той же карте по тому же ключу после проверки. Сначала - в том же операторе: в ветках if,
     * в условии, в {@code ?:}. Если там их нет, а проверка стоит в if, - в операторах, идущих за ним:
     * {@code if (!map.containsKey(key)) { throw ...; } value = map.get(key);}
     */
    private List<MethodCallExpr> repeats(MethodCallExpr check) {
        Optional<Statement> guard = check.findAncestor(Statement.class);
        if (guard.isEmpty()) {
            return List.of();
        }
        List<MethodCallExpr> inside = repeatsIn(guard.get(), check);
        if (!inside.isEmpty() || !(guard.get() instanceof IfStmt)) {
            return inside;
        }
        return guard.get().getParentNode()
                .filter(parent -> parent instanceof BlockStmt)
                .map(block -> repeatsIn(block, check).stream().filter(call -> !guard.get().isAncestorOf(call)).toList())
                .orElse(List.of());
    }

    private List<MethodCallExpr> repeatsIn(Node scope, MethodCallExpr check) {
        String map = receiver(check);
        String key = check.getArgument(0).toString();
        return scope.findAll(MethodCallExpr.class).stream()
                .filter(call -> GET.equals(call.getNameAsString()) || PUT.equals(call.getNameAsString()))
                .filter(call -> !call.getArguments().isEmpty() && call.getScope().isPresent())
                .filter(call -> receiver(call).equals(map) && call.getArgument(0).toString().equals(key))
                .filter(call -> isAfter(call, check))
                .toList();
    }

    private String message(MethodCallExpr check, List<MethodCallExpr> repeats) {
        String map = receiver(check);
        String key = check.getArgument(0).toString();
        String lookup = map + "." + GET + "(" + key + ")";
        List<MethodCallExpr> puts = repeats.stream().filter(call -> PUT.equals(call.getNameAsString())).toList();
        String repeat = repeats.stream().findFirst().map(this::shown).orElse(lookup);
        String start = "'" + Nodes.text(check) + "' и следом '" + repeat + "' ищут ключ в карте дважды: ";
        // map.put(key, map.get(key) + 1): новое значение считается из старого
        boolean accumulates = puts.stream()
                .anyMatch(put -> put.getArguments().size() > 1 && put.getArgument(1).toString().contains(lookup));
        if (accumulates) {
            return start + "значение обновляется из прежнего - это делает одна операция merge, например"
                    + " map.merge(key, 1, Integer::sum), либо compute";
        }
        if (!puts.isEmpty() && isNegated(check)) {
            return start + "значение кладут, только если ключа нет - это делает putIfAbsent, а когда значение дорого"
                    + " создавать - computeIfAbsent(key, k -> ...): он сам вернет значение, и отдельный get"
                    + " не понадобится";
        }
        if (!puts.isEmpty()) {
            return start + "значение заменяют, только если ключ есть - это делает replace(key, value) либо"
                    + " computeIfPresent";
        }
        return start + "возьмите значение одним вызовом и проверьте его на null (value = map.get(key);"
                + " if (value != null)) либо используйте getOrDefault; пара нужна, только если в карте хранят null";
    }

    private boolean isNegated(MethodCallExpr check) {
        return check.getParentNode()
                .filter(parent -> parent instanceof UnaryExpr unary
                        && unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT)
                .isPresent();
    }

    private String shown(MethodCallExpr call) {
        return PUT.equals(call.getNameAsString())
                ? receiver(call) + "." + PUT + "(" + call.getArgument(0) + ", ...)"
                : Nodes.text(call);
    }

    // values и this.values - одна и та же карта
    private String receiver(MethodCallExpr call) {
        String scope = call.getScope().map(Expression::toString).orElse("");
        return scope.startsWith(THIS_PREFIX) ? scope.substring(THIS_PREFIX.length()) : scope;
    }

    private boolean isAfter(Node later, Node earlier) {
        return later.getBegin().isPresent() && earlier.getBegin().isPresent()
                && later.getBegin().get().isAfter(earlier.getBegin().get());
    }
}
