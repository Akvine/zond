package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.Guards;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * null и коллекции из List.of(), Set.of(), Map.of(). Они не допускают null ни внутри, ни в запросе:
 * {@code List.of(a, null)} бросает NullPointerException при создании, а {@code List.of(a).contains(null)} -
 * при поиске, хотя у ArrayList тот же вызов просто вернул бы false.
 */
@Component
public class NullInImmutableCollectionRule extends AbstractRule {
    private static final Set<String> FACTORY_TYPES = Set.of("List", "Set", "Map");
    private static final Set<String> FACTORY_METHODS = Set.of("of", "ofEntries", "copyOf", "entry");
    // Запросы, которым у таких коллекций нельзя передать null
    private static final Set<String> LOOKUP_METHODS = Set.of(
            "contains", "indexOf", "lastIndexOf", "containsKey", "containsValue", "get", "getOrDefault");
    private static final Set<String> NULLABLE_ANNOTATIONS = Set.of("Nullable", "CheckForNull");
    // @RequestParam(required = false) String type: параметра в запросе может не быть
    private static final String OPTIONAL_PARAMETER = "required = false";
    private static final String OR_ELSE = "orElse";

    @Override
    public String code() {
        return RuleCodes.NULL_IN_IMMUTABLE_COLLECTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет null в List.of(), Set.of(), Map.of() и поиск null в таких коллекциях:"
                + " contains(null) бросает NullPointerException";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (isFactory(call) && call.getArguments().stream().map(Nodes::unwrap).anyMatch(Expression::isNullLiteralExpr)) {
                violations.add(violation(sourceFile, call,
                        "null в '" + describe(call) + "': такие коллекции null не допускают, вызов закончится"
                                + " NullPointerException; уберите null либо создайте обычную коллекцию -"
                                + " new ArrayList<>(...), Arrays.asList(...), new HashMap<>()"));
                continue;
            }
            if (!LOOKUP_METHODS.contains(call.getNameAsString()) || call.getArguments().isEmpty()
                    || call.getScope().isEmpty()) {
                continue;
            }
            Expression argument = Nodes.unwrap(call.getArgument(0));
            findSource(call.getScope().get())
                    .flatMap(source -> nullReason(argument).map(reason -> violation(sourceFile, call,
                            "'" + call + "' на коллекции из " + source + ": она не допускает null даже в запросе -"
                                    + " " + call.getNameAsString() + "(null) бросает NullPointerException, а не"
                                    + " возвращает false, - а " + reason + "; проверьте значение на null заранее"
                                    + " либо храните данные в обычной коллекции: new ArrayList<>(...),"
                                    + " new HashSet<>(...)")))
                    // Значение только может оказаться null - такая находка мягче
                    .map(violation -> argument.isNullLiteralExpr() ? violation : violation.withLevel(ErrorLevel.MAJOR))
                    .ifPresent(violations::add);
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    // List.of(...), Set.copyOf(...), Map.ofEntries(...), Map.entry(k, v)
    private boolean isFactory(MethodCallExpr call) {
        String name = call.getNameAsString();
        return FACTORY_METHODS.contains(name)
                && FACTORY_TYPES.stream().anyMatch(type -> MethodCalls.isCallOn(call, type, name));
    }

    private String describe(MethodCallExpr factory) {
        return factory.getScope().map(scope -> scope + ".").orElse("") + factory.getNameAsString() + "(...)";
    }

    /**
     * @return чем создана коллекция, если это List.of() и подобные: прямо в цепочке вызова либо в переменной,
     * которой после объявления ничего другого не присваивали
     */
    private Optional<String> findSource(Expression scope) {
        Expression value = Nodes.unwrap(scope);
        if (value.isMethodCallExpr()) {
            return Optional.of(value.asMethodCallExpr()).filter(this::isFactory).map(this::describe);
        }
        return LocalTypes.findInitializer(value)
                .map(Nodes::unwrap)
                .filter(Expression::isMethodCallExpr)
                .map(Expression::asMethodCallExpr)
                .filter(this::isFactory)
                .filter(factory -> !isReassigned(value))
                .map(this::describe);
    }

    private boolean isReassigned(Expression variable) {
        String name = MethodCalls.receiverName(variable);
        Node type = variable.getParentNode().orElse(null);
        while (type != null && !(type instanceof TypeDeclaration<?>)) {
            type = type.getParentNode().orElse(null);
        }
        return type != null && type.findAll(AssignExpr.class).stream()
                .map(AssignExpr::getTarget)
                .anyMatch(target -> MethodCalls.receiverName(target).equals(name));
    }

    /**
     * @return почему аргумент может оказаться null; пусто, если по коду этого не видно либо значение уже проверено
     */
    private Optional<String> nullReason(Expression argument) {
        if (argument.isNullLiteralExpr()) {
            return Optional.of("здесь ищут именно null");
        }
        // ALLOWED.contains(found.orElse(null))
        if (endsWithOrElseNull(argument)) {
            return Optional.of("'" + argument + "' дает null для пустого Optional");
        }
        if (!argument.isNameExpr()) {
            return Optional.empty();
        }
        String name = argument.asNameExpr().getNameAsString();
        if (Guards.isGuarded(argument, check -> Guards.isNullCheck(check, name))) {
            return Optional.empty();
        }
        Optional<Node> declaration = LocalTypes.findDeclaration(argument);
        if (declaration.isPresent() && declaration.get() instanceof Parameter parameter) {
            boolean nullable = Annotations.hasAny(parameter, NULLABLE_ANNOTATIONS)
                    || parameter.getAnnotations().stream().anyMatch(annotation -> annotation.toString().contains(OPTIONAL_PARAMETER));
            return nullable ? Optional.of("параметр '" + name + "' объявлен необязательным") : Optional.empty();
        }
        return declaration
                .filter(found -> found instanceof VariableDeclarator)
                .flatMap(found -> ((VariableDeclarator) found).getInitializer())
                .map(Nodes::unwrap)
                .filter(initializer -> initializer.isNullLiteralExpr() || endsWithOrElseNull(initializer))
                .map(initializer -> "в '" + name + "' может лежать null (" + initializer + ")");
    }

    private boolean endsWithOrElseNull(Expression expression) {
        return expression.isMethodCallExpr()
                && OR_ELSE.equals(expression.asMethodCallExpr().getNameAsString())
                && expression.asMethodCallExpr().getArguments().size() == 1
                && Nodes.unwrap(expression.asMethodCallExpr().getArgument(0)).isNullLiteralExpr();
    }
}
