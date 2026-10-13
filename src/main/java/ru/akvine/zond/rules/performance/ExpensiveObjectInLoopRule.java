package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loops;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Дорогой объект создается заново на каждой итерации, хотя от итерации не зависит.
 * <p>
 * {@code DateTimeFormatter.ofPattern} разбирает шаблон, {@code MessageDigest.getInstance} и фабрики XML
 * ищут реализацию среди провайдеров, {@code getDeclaredFields} копирует массив полей, HTTP-клиент
 * заводит пул соединений. Один раз это незаметно, в цикле на сто тысяч строк - основное время работы.
 * <p>
 * О регулярных выражениях в цикле сообщает jr:239, об {@code ObjectMapper} на каждый вызов - jr:240.
 */
@Component
public class ExpensiveObjectInLoopRule extends AbstractRule {

    private enum Kind {
        // Потокобезопасен и неизменяем: можно в константу
        SHAREABLE("создайте его один раз - объект потокобезопасен, ему место в константе"),
        // Хранит состояние: один на поток
        STATEFUL("создайте его один раз перед циклом и используйте повторно (в локальной переменной: объект"
                + " не потокобезопасен)"),
        FACTORY("поиск реализации идет по всем провайдерам на каждой итерации; создайте фабрику один раз"
                + " перед циклом"),
        REFLECTION("поиск по классу повторяется на каждой итерации и каждый раз копирует результат; сохраните"
                + " его перед циклом"),
        CLIENT("каждый клиент заводит свой пул соединений и потоки; создайте один клиент на приложение");

        private final String advice;

        Kind(String advice) {
            this.advice = advice;
        }
    }

    // Тип.метод - статические фабрики
    private static final Map<String, Kind> FACTORY_CALLS = Map.ofEntries(
            Map.entry("DateTimeFormatter.ofPattern", Kind.SHAREABLE),
            Map.entry("JAXBContext.newInstance", Kind.SHAREABLE),
            Map.entry("MessageDigest.getInstance", Kind.STATEFUL),
            Map.entry("Cipher.getInstance", Kind.STATEFUL),
            Map.entry("Mac.getInstance", Kind.STATEFUL),
            Map.entry("Signature.getInstance", Kind.STATEFUL),
            Map.entry("KeyFactory.getInstance", Kind.STATEFUL),
            Map.entry("SecretKeyFactory.getInstance", Kind.STATEFUL),
            Map.entry("NumberFormat.getInstance", Kind.STATEFUL),
            Map.entry("DocumentBuilderFactory.newInstance", Kind.FACTORY),
            Map.entry("SAXParserFactory.newInstance", Kind.FACTORY),
            Map.entry("XMLInputFactory.newInstance", Kind.FACTORY),
            Map.entry("XMLOutputFactory.newInstance", Kind.FACTORY),
            Map.entry("TransformerFactory.newInstance", Kind.FACTORY),
            Map.entry("XPathFactory.newInstance", Kind.FACTORY),
            Map.entry("SchemaFactory.newInstance", Kind.FACTORY),
            Map.entry("DatatypeFactory.newInstance", Kind.FACTORY),
            Map.entry("HttpClient.newHttpClient", Kind.CLIENT),
            Map.entry("HttpClient.newBuilder", Kind.CLIENT),
            Map.entry("WebClient.create", Kind.CLIENT),
            Map.entry("WebClient.builder", Kind.CLIENT),
            Map.entry("RestClient.create", Kind.CLIENT),
            Map.entry("RestClient.builder", Kind.CLIENT),
            Map.entry("Class.forName", Kind.REFLECTION));

    private static final Map<String, Kind> CREATIONS = Map.of(
            "SimpleDateFormat", Kind.STATEFUL,
            "DecimalFormat", Kind.STATEFUL,
            "SecureRandom", Kind.SHAREABLE,
            "RestTemplate", Kind.CLIENT,
            "OkHttpClient", Kind.CLIENT);

    private static final Set<String> REFLECTION_LOOKUPS = Set.of(
            "getDeclaredFields", "getDeclaredMethods", "getDeclaredField", "getDeclaredMethod", "getMethods",
            "getMethod", "getFields", "getField", "getDeclaredConstructors", "getDeclaredConstructor",
            "getConstructor");
    private static final String GET_CLASS = "getClass";
    private static final String CLASS = "Class";
    private static final Set<UnaryExpr.Operator> STEPS = Set.of(
            UnaryExpr.Operator.PREFIX_INCREMENT, UnaryExpr.Operator.PREFIX_DECREMENT,
            UnaryExpr.Operator.POSTFIX_INCREMENT, UnaryExpr.Operator.POSTFIX_DECREMENT);

    @Override
    public String code() {
        return RuleCodes.EXPENSIVE_OBJECT_IN_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет дорогие объекты, которые создаются заново на каждой итерации цикла:"
                + " форматтеры, MessageDigest, фабрики XML, HTTP-клиенты, поиск через рефлексию";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            kind(call).filter(kind -> isRepeatedInvariant(call))
                    .ifPresent(kind -> violations.add(violation(sourceFile, call.getName(), message(call, kind))));
        }
        for (ObjectCreationExpr creation : sourceFile.unit().findAll(ObjectCreationExpr.class)) {
            Optional.ofNullable(CREATIONS.get(creation.getType().getNameAsString()))
                    .filter(kind -> isRepeatedInvariant(creation))
                    .ifPresent(kind -> violations.add(violation(sourceFile, creation, message(creation, kind))));
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

    private Optional<Kind> kind(MethodCallExpr call) {
        if (call.getScope().isEmpty()) {
            return Optional.empty();
        }
        Expression scope = Nodes.unwrap(call.getScope().get());
        String name = call.getNameAsString();
        Kind factory = FACTORY_CALLS.get(MethodCalls.receiverName(scope) + "." + name);
        if (factory != null && scope.isNameExpr()) {
            return Optional.of(factory);
        }
        return REFLECTION_LOOKUPS.contains(name) && isClassObject(scope) ? Optional.of(Kind.REFLECTION) : Optional.empty();
    }

    // Order.class, value.getClass(), переменная типа Class
    private boolean isClassObject(Expression scope) {
        return scope.isClassExpr()
                || scope.isMethodCallExpr() && GET_CLASS.equals(scope.asMethodCallExpr().getNameAsString())
                || LocalTypes.typeOf(scope).filter(CLASS::equals).isPresent();
    }

    /**
     * @return true, если выражение повторяется в цикле по данным и ничего из этого цикла не использует:
     * результат на каждой итерации тот же, и его можно получить один раз
     */
    private boolean isRepeatedInvariant(Expression expression) {
        if (TestClasses.isInside(expression) || CodeContexts.isStartup(expression)) {
            return false;
        }
        Optional<Node> loop = Loops.enclosingIteration(expression);
        while (loop.isPresent() && CodeContexts.isRetryLoop(loop.get())) {
            loop = Loops.enclosingIteration(loop.get());
        }
        return loop.filter(iteration -> isInvariant(expression, iteration)).isPresent();
    }

    /**
     * {@code DateTimeFormatter.ofPattern(row.pattern())} зависит от строки: вынести его из цикла нельзя.
     * Зависит от итерации и переменная, объявленная до цикла, если в цикле ей присваивают:
     * {@code while (type != null) { type.getDeclaredFields(); type = type.getSuperclass(); }}
     */
    private boolean isInvariant(Expression expression, Node loop) {
        Set<String> changed = changedIn(loop);
        List<NameExpr> names = expression.findAll(NameExpr.class);
        return names.stream().noneMatch(name -> changed.contains(name.getNameAsString()))
                && names.stream()
                .map(LocalTypes::findDeclaration)
                .flatMap(Optional::stream)
                .allMatch(declaration -> Loops.isDeclaredOutside(loop, declaration));
    }

    // Имена, которым в цикле присваивают: type = ..., index++, this.type = ...
    private Set<String> changedIn(Node loop) {
        Set<String> changed = new HashSet<>();
        loop.findAll(AssignExpr.class).forEach(assignment -> changed.add(simpleName(assignment.getTarget())));
        loop.findAll(UnaryExpr.class).stream()
                .filter(step -> STEPS.contains(step.getOperator()))
                .forEach(step -> changed.add(simpleName(step.getExpression())));
        return changed;
    }

    private String simpleName(Expression target) {
        return target.isFieldAccessExpr() ? target.asFieldAccessExpr().getNameAsString() : target.toString();
    }

    private String message(Expression expression, Kind kind) {
        return "'" + Nodes.text(expression) + "' выполняется заново на каждой итерации, хотя от нее не зависит: "
                + kind.advice;
    }
}
