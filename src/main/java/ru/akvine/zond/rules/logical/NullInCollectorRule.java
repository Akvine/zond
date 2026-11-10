package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodReferences;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ProjectClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * null в коллекторах. Collectors.toMap бросает NullPointerException на значении null - даже когда итоговая
 * HashMap такие значения хранить умеет, - а groupingBy бросает его на ключе null.
 */
@Component
public class NullInCollectorRule extends AbstractRule implements ProjectRule {
    private static final Set<String> TO_MAP = Set.of("toMap", "toUnmodifiableMap", "toConcurrentMap");
    private static final Set<String> GROUPING = Set.of("groupingBy", "groupingByConcurrent");
    private static final String OR_ELSE = "orElse";
    private static final String GET = "get";
    private static final String NULLABLE = "Nullable";
    private static final Set<String> MAP_TYPES = Set.of(
            "Map", "HashMap", "LinkedHashMap", "TreeMap", "ConcurrentHashMap", "ConcurrentMap", "SortedMap");

    @Override
    public String code() {
        return RuleCodes.NULL_IN_COLLECTOR_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет null в коллекторах: значение null в Collectors.toMap и ключ null в groupingBy"
                + " заканчиваются NullPointerException";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
                String name = call.getNameAsString();
                boolean toMap = TO_MAP.contains(name) && call.getArguments().size() >= 2;
                boolean grouping = GROUPING.contains(name) && !call.getArguments().isEmpty();
                if (!toMap && !grouping) {
                    continue;
                }
                Expression mapper = Nodes.unwrap(call.getArgument(toMap ? 1 : 0));
                describeNull(mapper, classes).ifPresent(reason -> violations.add(violation(sourceFile, mapper,
                        (toMap
                                ? "Значение для Collectors." + name + "(...) может оказаться null (" + reason + "):"
                                + " коллектор кладет записи через merge и на значении null бросает"
                                + " NullPointerException, хотя сама HashMap такие значения хранить умеет"
                                : "Ключ для Collectors." + name + "(...) может оказаться null (" + reason + "):"
                                + " коллектор бросает NullPointerException - element cannot be mapped to a null key")
                                + "; отфильтруйте такие элементы заранее (filter), подставьте значение по умолчанию"
                                + (toMap ? " либо соберите карту циклом: map.put(key, value)" : ""))));
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

    /**
     * @return почему функция может вернуть null; пусто, если по коду этого не видно
     */
    private Optional<String> describeNull(Expression mapper, ProjectClasses classes) {
        // Order::getComment, где поле comment помечено @Nullable
        if (mapper.isMethodReferenceExpr()) {
            return MethodReferences.ownerOf(mapper.asMethodReferenceExpr(), classes)
                    .filter(owner -> isNullableProperty(owner, mapper.asMethodReferenceExpr().getIdentifier()))
                    .map(owner -> "'" + Nodes.text(mapper) + "' помечен @Nullable");
        }
        if (!mapper.isLambdaExpr()) {
            return Optional.empty();
        }
        LambdaExpr lambda = mapper.asLambdaExpr();
        Optional<Expression> body = lambda.getExpressionBody().map(Nodes::unwrap);
        if (body.isEmpty()) {
            return Optional.empty();
        }
        Expression value = body.get();
        if (value.isNullLiteralExpr() || value.isConditionalExpr() && hasNullBranch(value)) {
            return Optional.of("функция возвращает null");
        }
        if (!value.isMethodCallExpr()) {
            return Optional.empty();
        }
        MethodCallExpr result = value.asMethodCallExpr();
        // ...findFirst().orElse(null)
        if (OR_ELSE.equals(result.getNameAsString()) && result.getArguments().size() == 1
                && result.getArgument(0).isNullLiteralExpr()) {
            return Optional.of("функция кончается на orElse(null)");
        }
        // names.get(order.getId()): для неизвестного ключа Map.get возвращает null
        boolean lookup = GET.equals(result.getNameAsString()) && result.getArguments().size() == 1
                && result.getScope().flatMap(LocalTypes::typeOf).filter(MAP_TYPES::contains).isPresent();
        if (lookup) {
            return Optional.of("'" + Nodes.text(result) + "' для неизвестного ключа возвращает null");
        }
        // order -> order.getComment() с полем @Nullable
        boolean nullableGetter = result.getArguments().isEmpty() && result.getScope()
                .flatMap(LocalTypes::typeOf)
                .flatMap(classes::find)
                .filter(owner -> isNullableProperty(owner, result.getNameAsString()))
                .isPresent();
        return nullableGetter ? Optional.of("'" + Nodes.text(result) + "' помечен @Nullable") : Optional.empty();
    }

    private boolean hasNullBranch(Expression conditional) {
        return Nodes.unwrap(conditional.asConditionalExpr().getThenExpr()).isNullLiteralExpr()
                || Nodes.unwrap(conditional.asConditionalExpr().getElseExpr()).isNullLiteralExpr();
    }

    // @Nullable стоит на геттере либо на поле, которое он читает: getComment -> comment
    private boolean isNullableProperty(ClassOrInterfaceDeclaration owner, String getter) {
        boolean onGetter = owner.getMethodsByName(getter).stream()
                .filter(method -> method.getParameters().isEmpty())
                .anyMatch(this::isNullable);
        String property = getter.replaceFirst("^(get|is)", "");
        if (onGetter || property.isEmpty()) {
            return onGetter;
        }
        String field = Character.toLowerCase(property.charAt(0)) + property.substring(1);
        return owner.getFields().stream()
                .filter(candidate -> candidate.getVariables().stream()
                        .anyMatch(variable -> variable.getNameAsString().equals(field)))
                .anyMatch(this::isNullable);
    }

    private boolean isNullable(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream().anyMatch(annotation -> NULLABLE.equals(annotation.getName().getIdentifier()));
    }
}
