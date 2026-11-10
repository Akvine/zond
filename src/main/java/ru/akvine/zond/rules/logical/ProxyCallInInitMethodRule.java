package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.Type;
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
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ProxyAnnotations;
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.TransactionalAnnotations;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Расчет на Spring-прокси в методе инициализации бина. {@code @PostConstruct} и {@code afterPropertiesSet()}
 * контейнер вызывает на самом объекте, а прокси для бина создает после них. Поэтому в это время не работают
 * ни аннотации на самом методе инициализации, ни вызов своих методов через прокси: ссылка на свой бин
 * (self, getBean, ObjectProvider) либо отдаст недостроенный объект без прокси, либо закончится
 * BeanCurrentlyInCreationException. Вызов через this идет мимо прокси всегда: о нем сообщают правила
 * о вызове из того же класса.
 */
@Component
public class ProxyCallInInitMethodRule extends AbstractRule {
    private static final String POST_CONSTRUCT = "PostConstruct";
    private static final String AFTER_PROPERTIES_SET = "afterPropertiesSet";
    private static final String INITIALIZING_BEAN = "InitializingBean";
    private static final String TRANSACTIONAL = "Transactional";
    private static final String ASYNC = "Async";
    private static final String GET_BEAN = "getBean";

    // ObjectProvider<OrderService> self: бин достают из контейнера при вызове
    private static final Set<String> PROVIDER_TYPES = Set.of("ObjectProvider", "Provider", "ObjectFactory", "Lazy", "Supplier");
    private static final Set<String> PROVIDER_METHODS = Set.of("getObject", "getIfAvailable", "getIfUnique", "get");
    // Поле с типом интерфейса, который класс реализует, считается ссылкой на себя только по имени: self, selfProxy
    private static final Pattern SELF_NAME = Pattern.compile(".*(self|proxy)$|^me$", Pattern.CASE_INSENSITIVE);

    // init() -> warmUp() -> self.load(): дальше код инициализации обычно не уходит
    private static final int MAX_CALL_DEPTH = 2;

    private static final String ADVICE = " перенесите работу в обработчик ApplicationReadyEvent, ApplicationRunner"
            + " или SmartInitializingSingleton: к этому времени прокси уже созданы";
    private static final String TRANSACTION_ADVICE = " либо откройте транзакцию явно через TransactionTemplate";

    /**
     * Метод, который выполняется при инициализации бина
     *
     * @param init  метод инициализации, от которого до него дошли
     * @param chain как дошли: 'init' -> 'warmUp'
     */
    private record Reached(MethodDeclaration method, MethodDeclaration init, String chain) {
    }

    @Override
    public String code() {
        return RuleCodes.PROXY_CALL_IN_INIT_METHOD_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет в @PostConstruct и afterPropertiesSet() расчет на Spring-прокси: @Transactional,"
                + " @Async, @Retryable на самом методе инициализации и вызов таких методов через ссылку на свой бин";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (type.isInterface()) {
                continue;
            }
            List<MethodDeclaration> initMethods = type.getMethods().stream()
                    .filter(method -> isInitMethod(method, type))
                    .toList();
            if (initMethods.isEmpty()) {
                continue;
            }
            List<Reached> reached = reachedFrom(initMethods, type);
            for (MethodDeclaration init : initMethods) {
                checkOwnAnnotation(sourceFile, type, init, reached, violations);
            }

            // На приватных и статических методах прокси не работает в принципе
            List<MethodDeclaration> proxied = type.getMethods().stream()
                    .filter(method -> !method.isPrivate() && !method.isStatic())
                    .filter(method -> ProxyAnnotations.effectiveBehavior(method).isPresent())
                    .toList();
            if (!proxied.isEmpty()) {
                reached.forEach(place -> checkSelfCalls(sourceFile, type, place, proxied, violations));
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

    private boolean isInitMethod(MethodDeclaration method, ClassOrInterfaceDeclaration type) {
        if (Annotations.has(method, POST_CONSTRUCT)) {
            return true;
        }
        return AFTER_PROPERTIES_SET.equals(method.getNameAsString()) && method.getParameters().isEmpty()
                && type.getImplementedTypes().stream()
                .anyMatch(implemented -> INITIALIZING_BEAN.equals(implemented.getNameAsString()));
    }

    // Аннотация стоит на самом методе инициализации либо на классе, а метод работает с базой
    private void checkOwnAnnotation(
            SourceFile sourceFile, ClassOrInterfaceDeclaration type, MethodDeclaration init, List<Reached> reached,
            List<Violation> violations) {
        Optional<AnnotationExpr> own = ProxyAnnotations.behavior(init);
        if (own.isPresent()) {
            violations.add(violation(sourceFile, own.get(),
                    "@" + own.get().getNameAsString() + " на методе инициализации '" + init.getNameAsString()
                            + "' не действует: контейнер вызывает его на самом объекте, до того как для бина"
                            + " создан прокси, - " + consequence(own.get()) + ";" + ADVICE
                            + (isTransactional(own.get()) ? TRANSACTION_ADVICE : "")));
            return;
        }
        // @Transactional на классе читается как "все открытые методы в транзакции", но к инициализации
        // это не относится. Находка - только если транзакция там нужна: метод обращается к базе
        Optional<AnnotationExpr> onClass = TransactionalAnnotations.find(type);
        boolean usesDatabase = reached.stream()
                .filter(place -> place.init() == init)
                .flatMap(place -> place.method().findAll(MethodCallExpr.class).stream())
                .anyMatch(Repositories::isRepositoryCall);
        if (onClass.isPresent() && init.isPublic() && usesDatabase) {
            violations.add(violation(sourceFile, init,
                    "@Transactional на классе '" + type.getNameAsString() + "' на метод инициализации '"
                            + init.getNameAsString() + "' не действует: контейнер вызывает его на самом объекте,"
                            + " до того как для бина создан прокси, - обращения к базе пойдут без общей"
                            + " транзакции;" + ADVICE + TRANSACTION_ADVICE));
        }
    }

    private void checkSelfCalls(
            SourceFile sourceFile, ClassOrInterfaceDeclaration type, Reached place, List<MethodDeclaration> proxied,
            List<Violation> violations) {
        for (MethodCallExpr call : place.method().findAll(MethodCallExpr.class)) {
            if (call.getScope().isEmpty() || enclosingType(call) != type || !isSelfReference(call.getScope().get(), type)) {
                continue;
            }
            Optional<MethodDeclaration> callee = MethodCalls.findCallee(call, proxied);
            Optional<AnnotationExpr> annotation = callee.flatMap(ProxyAnnotations::effectiveBehavior);
            if (callee.isEmpty() || annotation.isEmpty()) {
                continue;
            }
            String from = place.method() == place.init()
                    ? "из метода инициализации " + place.chain()
                    : "из метода '" + place.method().getNameAsString() + "', который выполняется при инициализации ("
                    + place.chain() + ")";
            violations.add(violation(sourceFile, call,
                    "Вызов @" + annotation.get().getNameAsString() + "-метода '" + callee.get().getNameAsString()
                            + "' через ссылку на свой же бин ('" + Nodes.unwrap(call.getScope().get()) + "') " + from
                            + ": бин в это время еще создается, и готового прокси у него нет - вызов пройдет мимо"
                            + " прокси (" + consequence(annotation.get()) + ") либо закончится"
                            + " BeanCurrentlyInCreationException;" + ADVICE));
        }
    }

    // Метод инициализации выполняет поток запуска приложения: @Async от него работу не уводит
    private String consequence(AnnotationExpr annotation) {
        return ASYNC.equals(annotation.getName().getIdentifier())
                ? "метод выполнится синхронно, в потоке запуска приложения"
                : ProxyAnnotations.consequence(annotation);
    }

    private boolean isTransactional(AnnotationExpr annotation) {
        return TRANSACTIONAL.equals(annotation.getName().getIdentifier());
    }

    /**
     * @return методы инициализации и свои методы, которые они вызывают напрямую: такой вызов идет мимо прокси,
     * и вызванный метод выполняется там же, при инициализации
     */
    private List<Reached> reachedFrom(List<MethodDeclaration> initMethods, ClassOrInterfaceDeclaration type) {
        List<Reached> reached = new ArrayList<>();
        Set<MethodDeclaration> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Reached> queue = new ArrayDeque<>();
        Deque<Integer> depths = new ArrayDeque<>();
        for (MethodDeclaration init : initMethods) {
            if (seen.add(init)) {
                queue.add(new Reached(init, init, "'" + init.getNameAsString() + "'"));
                depths.add(0);
            }
        }
        while (!queue.isEmpty()) {
            Reached current = queue.removeFirst();
            int depth = depths.removeFirst();
            reached.add(current);
            if (depth == MAX_CALL_DEPTH) {
                continue;
            }
            for (MethodCallExpr call : current.method().findAll(MethodCallExpr.class)) {
                if (!isOwnCall(call, type)) {
                    continue;
                }
                Optional<MethodDeclaration> callee = MethodCalls.findCallee(call, type.getMethods());
                if (callee.isPresent() && seen.add(callee.get())) {
                    queue.add(new Reached(callee.get(), current.init(),
                            current.chain() + " -> '" + callee.get().getNameAsString() + "'"));
                    depths.add(depth + 1);
                }
            }
        }
        return reached;
    }

    // load() или this.load() прямо в этом классе, а не во вложенном
    private boolean isOwnCall(MethodCallExpr call, ClassOrInterfaceDeclaration type) {
        boolean ownScope = call.getScope()
                .map(scope -> scope.isThisExpr() && scope.asThisExpr().getTypeName().isEmpty())
                .orElse(true);
        return ownScope && enclosingType(call) == type;
    }

    private Node enclosingType(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null && !(current instanceof TypeDeclaration<?>)) {
            current = current.getParentNode().orElse(null);
        }
        return current;
    }

    /**
     * @return true, если метод вызывают на своем же бине, взятом у контейнера: поле self, context.getBean(Own.class),
     * selfProvider.getObject(), ((Own) AopContext.currentProxy())
     */
    private boolean isSelfReference(Expression scope, ClassOrInterfaceDeclaration type) {
        Expression value = Nodes.unwrap(scope);
        String own = type.getNameAsString();
        if (value.isCastExpr()) {
            return own.equals(LocalTypes.typeName(value.asCastExpr().getType()));
        }
        if (value.isMethodCallExpr()) {
            MethodCallExpr source = value.asMethodCallExpr();
            if (GET_BEAN.equals(source.getNameAsString())) {
                return source.getArguments().stream()
                        .anyMatch(argument -> argument.isClassExpr()
                                && own.equals(LocalTypes.typeName(argument.asClassExpr().getType())));
            }
            return PROVIDER_METHODS.contains(source.getNameAsString())
                    && source.getScope()
                    .flatMap(this::declaredType)
                    .filter(provider -> provides(provider, own))
                    .isPresent();
        }
        if (!value.isNameExpr() && !(value.isFieldAccessExpr() && value.asFieldAccessExpr().getScope().isThisExpr())) {
            return false;
        }
        Optional<String> declared = declaredType(value).map(LocalTypes::typeName);
        if (declared.filter(own::equals).isPresent()) {
            return true;
        }
        // private OrderService self; в классе OrderServiceImpl implements OrderService
        boolean ownInterface = declared.isPresent() && type.getImplementedTypes().stream()
                .anyMatch(implemented -> implemented.getNameAsString().equals(declared.get()));
        return ownInterface && SELF_NAME.matcher(MethodCalls.receiverName(value)).matches();
    }

    private Optional<Type> declaredType(Expression expression) {
        return LocalTypes.findDeclaration(expression).flatMap(declaration -> {
            if (declaration instanceof VariableDeclarator variable) {
                return Optional.of(variable.getType());
            }
            return declaration instanceof Parameter parameter ? Optional.of(parameter.getType()) : Optional.empty();
        });
    }

    // ObjectProvider<OrderService> в классе OrderService
    private boolean provides(Type provider, String own) {
        return provider.isClassOrInterfaceType()
                && PROVIDER_TYPES.contains(provider.asClassOrInterfaceType().getNameAsString())
                && provider.asClassOrInterfaceType().getTypeArguments()
                .filter(arguments -> arguments.size() == 1 && own.equals(LocalTypes.typeName(arguments.get(0))))
                .isPresent();
    }
}
