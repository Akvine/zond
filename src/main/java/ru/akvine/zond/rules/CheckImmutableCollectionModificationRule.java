package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;

import java.util.Set;

@Component
public class CheckImmutableCollectionModificationRule extends AbstractUnmodifiableCollectionRule {
    private static final String COLLECTIONS = "Collections";
    private static final String STREAM_TO_LIST = "toList";

    // Map.of(...), List.copyOf(...) и т.п.
    private static final Set<String> FACTORY_TYPES = Set.of("Map", "List", "Set");
    private static final Set<String> FACTORY_METHODS = Set.of("of", "ofEntries", "copyOf");

    private static final Set<String> COLLECTIONS_METHODS = Set.of(
            "emptyList", "emptySet", "emptyMap", "singletonList", "singleton", "singletonMap",
            "unmodifiableList", "unmodifiableSet", "unmodifiableMap", "unmodifiableCollection");

    @Override
    public String code() {
        return RuleCodes.CHECK_IMMUTABLE_COLLECTION_MODIFICATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет изменение неизменяемых коллекций: Map.of(), List.of(), Stream.toList()";
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    @Override
    protected boolean isUnmodifiableSource(MethodCallExpr call) {
        String name = call.getNameAsString();
        if (FACTORY_METHODS.contains(name)) {
            return FACTORY_TYPES.stream().anyMatch(type -> MethodCalls.isCallOn(call, type, name));
        }
        if (COLLECTIONS_METHODS.contains(name)) {
            return MethodCalls.isCallOn(call, COLLECTIONS, name);
        }
        // stream.toList() в отличие от collect(Collectors.toList()) возвращает неизменяемый список
        return STREAM_TO_LIST.equals(name) && call.getArguments().isEmpty() && call.getScope().isPresent();
    }

    @Override
    protected Set<String> modifyingMethods() {
        return Set.of(
                "add", "addAll", "remove", "removeAll", "removeIf", "retainAll", "clear", "set", "sort", "replaceAll",
                "put", "putAll", "putIfAbsent", "compute", "computeIfAbsent", "computeIfPresent", "merge", "replace");
    }

    @Override
    protected String message(MethodCallExpr call, String source) {
        return "'" + call.getNameAsString() + "(...)' на неизменяемой коллекции из " + source + ": вызов закончится"
                + " UnsupportedOperationException; создайте изменяемую копию: new HashMap<>(...),"
                + " new ArrayList<>(...)";
    }
}
