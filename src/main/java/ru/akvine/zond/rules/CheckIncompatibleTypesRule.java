package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckIncompatibleTypesRule extends AbstractRule {
    private static final String EQUALS = "equals";
    private static final String REMOVE = "remove";
    private static final String INT = "int";
    private static final String INTEGER = "Integer";

    private static final Map<String, String> WRAPPERS = Map.of(
            "int", "Integer", "long", "Long", "short", "Short", "byte", "Byte",
            "double", "Double", "float", "Float", "boolean", "Boolean", "char", "Character");

    // Типы, объекты которых не равны объектам никакого другого типа из этого же списка
    private static final Set<String> DISTINCT_TYPES = Set.of(
            "String", "Integer", "Long", "Short", "Byte", "Double", "Float", "Boolean", "Character",
            "BigDecimal", "BigInteger", "UUID", "LocalDate", "LocalDateTime", "Instant");

    private static final Set<String> LIST_TYPES = Set.of("List", "ArrayList", "LinkedList");
    private static final Set<String> COLLECTION_TYPES = Set.of(
            "List", "ArrayList", "LinkedList", "Set", "HashSet", "LinkedHashSet", "TreeSet", "Collection", "Queue", "Deque");
    private static final Set<String> MAP_TYPES = Set.of("Map", "HashMap", "LinkedHashMap", "TreeMap", "ConcurrentHashMap");

    private static final Set<String> ELEMENT_METHODS = Set.of("contains", "indexOf", "lastIndexOf", "remove");
    private static final Set<String> KEY_METHODS = Set.of("get", "containsKey", "remove", "getOrDefault");
    private static final String CONTAINS_VALUE = "containsValue";

    @Override
    public String code() {
        return RuleCodes.CHECK_INCOMPATIBLE_TYPES_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет equals, contains, Map.get и remove с аргументом несовместимого типа";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (call.getScope().isEmpty() || call.getArguments().isEmpty()) {
                continue;
            }
            describeProblem(call, Nodes.unwrap(call.getScope().get()), call.getArgument(0))
                    .ifPresent(problem -> violations.add(violation(sourceFile, call, "'" + call + "': " + problem)));
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

    private Optional<String> describeProblem(MethodCallExpr call, Expression scope, Expression argument) {
        String method = call.getNameAsString();
        Optional<String> argumentType = LocalTypes.typeOf(argument);
        if (EQUALS.equals(method) && call.getArguments().size() == 1) {
            return describeMismatch(family(LocalTypes.typeOf(scope)), family(argumentType),
                    "объекты разных типов не равны никогда, результат всегда false");
        }

        Optional<Type> declared = declaredType(scope);
        String container = declared.map(LocalTypes::typeName).orElse("");
        if (COLLECTION_TYPES.contains(container) && ELEMENT_METHODS.contains(method)) {
            Optional<String> element = typeArgument(declared.get(), 0);
            // list.remove(1) у List<Integer> удаляет элемент с индексом 1, а не число 1
            if (REMOVE.equals(method) && LIST_TYPES.contains(container) && argumentType.filter(INT::equals).isPresent()) {
                return element.filter(INTEGER::equals).map(type -> "у List<Integer> вызов remove(int) удаляет"
                        + " элемент по индексу, а не значение; для значения передайте Integer.valueOf(...)");
            }
            return describeMismatch(family(element), family(argumentType),
                    "в коллекции лежат значения другого типа - совпадения не будет никогда");
        }
        if (MAP_TYPES.contains(container) && (KEY_METHODS.contains(method) || CONTAINS_VALUE.equals(method))) {
            int index = CONTAINS_VALUE.equals(method) ? 1 : 0;
            return describeMismatch(family(typeArgument(declared.get(), index)), family(argumentType),
                    "ключи и значения Map другого типа - совпадения не будет никогда");
        }
        return Optional.empty();
    }

    private Optional<String> describeMismatch(Optional<String> expected, Optional<String> actual, String consequence) {
        if (expected.isEmpty() || actual.isEmpty() || expected.equals(actual)) {
            return Optional.empty();
        }
        return Optional.of("сравниваются " + expected.get() + " и " + actual.get() + ": " + consequence
                + " (1 и 1L - разные объекты); приведите значение к нужному типу");
    }

    // int -> Integer; пусто для типов, о несовместимости которых судить нельзя
    private Optional<String> family(Optional<String> type) {
        return type.map(name -> WRAPPERS.getOrDefault(name, name)).filter(DISTINCT_TYPES::contains);
    }

    // Тип, которым переменная объявлена: по нему видны параметры List<Long>, Map<String, User>
    private Optional<Type> declaredType(Expression scope) {
        Optional<Node> declaration = LocalTypes.findDeclaration(scope);
        if (declaration.isEmpty()) {
            return Optional.empty();
        }
        if (declaration.get() instanceof Parameter parameter) {
            return Optional.of(parameter.getType());
        }
        return declaration.get() instanceof VariableDeclarator variable ? Optional.of(variable.getType()) : Optional.empty();
    }

    private Optional<String> typeArgument(Type type, int index) {
        if (!type.isClassOrInterfaceType()) {
            return Optional.empty();
        }
        return type.asClassOrInterfaceType().getTypeArguments()
                .filter(arguments -> arguments.size() > index)
                .map(arguments -> LocalTypes.typeName(arguments.get(index)));
    }
}
