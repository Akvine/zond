package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.BeanScopes;
import ru.akvine.zond.rules.support.Guards;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Lombok;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class MutableStateInSingletonBeanRule implements ProjectRule {
    private static final Set<String> BEAN_ANNOTATIONS =
            Set.of("Component", "Service", "Repository", "Controller", "RestController");

    // Сеттеры такого класса вызывает Spring при старте, а не прикладной код
    private static final String CONFIGURATION_PROPERTIES = "ConfigurationProperties";

    // Поле или метод, которыми управляет контейнер: значение задается один раз при создании бина
    // либо сбрасывается при остановке (@PreDestroy), когда бин уже не обслуживает запросы
    private static final Set<String> CONTAINER_MANAGED_ANNOTATIONS = Set.of(
            "Autowired", "Inject", "Resource", "Value", "PersistenceContext", "PersistenceUnit", "PostConstruct",
            "PreDestroy");
    // destroy() у DisposableBean контейнер вызывает при остановке
    private static final String DESTROY = "destroy";
    private static final String DISPOSABLE_BEAN = "DisposableBean";
    private static final String SETTER = "set";

    @Override
    public String name() {
        return getClass().getSimpleName();
    }

    @Override
    public String code() {
        return RuleCodes.MUTABLE_STATE_IN_SINGLETON_BEAN_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет поля singleton-бинов, которые изменяются в методах без синхронизации";
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        Map<String, List<MethodCallExpr>> setterCalls = setterCalls(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            checkFile(sourceFile, setterCalls, violations);
        }
        return violations;
    }

    private void checkFile(SourceFile sourceFile, Map<String, List<MethodCallExpr>> setterCalls, List<Violation> violations) {
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (!isSingletonBean(type)) {
                continue;
            }

            for (FieldDeclaration field : type.getFields()) {
                if (field.isStatic() || field.isFinal() || field.isVolatile() || isContainerManaged(field)) {
                    continue;
                }

                for (VariableDeclarator variable : field.getVariables()) {
                    Set<String> writers = findWriters(type, variable.getNameAsString());
                    // Сеттера в коде нет, но Lombok его создаст - и поле сможет поменять любой, у кого есть бин.
                    // Если его вызывают только при запуске (из @PostConstruct другого бина) либо не вызывают
                    // вовсе, поле во время работы не меняется
                    if (Lombok.hasSetter(type, field)
                            && isCalledAtRuntime(type, setterName(variable.getNameAsString()), setterCalls)) {
                        writers.add("'" + setterName(variable.getNameAsString()) + "' (сеттер от Lombok)");
                    }
                    if (writers.isEmpty()) {
                        continue;
                    }
                    violations.add(new Violation(
                            errorLevel(),
                            errorType(),
                            code(),
                            name(),
                            sourceFile.path(),
                            variable.getBegin().map(position -> position.line).orElse(0),
                            "Поле '" + variable.getNameAsString() + "' singleton-бина '" + type.getNameAsString()
                                    + "' изменяется без синхронизации в методе " + String.join(", ", writers)
                                    + ": экземпляр один на все потоки, возможны гонки и утечка данных"
                                    + " между запросами"));
                }
            }
        }
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CONCURRENCY;
    }

    // @Scope("prototype"), @RequestScope, @StepScope - у каждого запроса или шага свой экземпляр
    private boolean isSingletonBean(ClassOrInterfaceDeclaration type) {
        Set<String> annotations = annotationNames(type);
        return annotations.stream().anyMatch(BEAN_ANNOTATIONS::contains)
                && !annotations.contains(CONFIGURATION_PROPERTIES)
                && !BeanScopes.isShortLived(type);
    }

    // Все вызовы set*(...) проекта по имени метода: сеттеры от Lombok в коде не объявлены, их ищут по вызовам
    private Map<String, List<MethodCallExpr>> setterCalls(List<SourceFile> sourceFiles) {
        Map<String, List<MethodCallExpr>> calls = new HashMap<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
                if (call.getNameAsString().startsWith(SETTER) && call.getArguments().size() == 1) {
                    calls.computeIfAbsent(call.getNameAsString(), name -> new ArrayList<>()).add(call);
                }
            }
        }
        return calls;
    }

    // Вызов сеттера этого бина вне запуска. Объект, тип которого не определить, считается этим бином
    private boolean isCalledAtRuntime(ClassOrInterfaceDeclaration type, String setter,
                                      Map<String, List<MethodCallExpr>> setterCalls) {
        String typeName = type.getNameAsString();
        return setterCalls.getOrDefault(setter, List.of()).stream()
                .filter(call -> call.getScope()
                        .filter(scope -> !scope.isThisExpr())
                        .map(scope -> LocalTypes.typeOf(scope).map(typeName::equals).orElse(true))
                        .orElseGet(() -> call.findAncestor(ClassOrInterfaceDeclaration.class)
                                .filter(owner -> owner == type)
                                .isPresent()))
                .anyMatch(call -> !CodeContexts.isStartup(call));
    }

    private boolean isShutdownCallback(MethodDeclaration method) {
        return DESTROY.equals(method.getNameAsString()) && method.getParameters().isEmpty()
                && method.findAncestor(ClassOrInterfaceDeclaration.class)
                .filter(owner -> owner.getImplementedTypes().stream()
                        .anyMatch(implemented -> DISPOSABLE_BEAN.equals(implemented.getNameAsString())))
                .isPresent();
    }

    private boolean isContainerManaged(NodeWithAnnotations<?> node) {
        return annotationNames(node).stream().anyMatch(CONTAINER_MANAGED_ANNOTATIONS::contains);
    }

    /**
     * @return имена методов, в которых полю присваивается значение; конструкторы сюда не попадают
     */
    // name -> setName
    private String setterName(String fieldName) {
        return SETTER + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
    }

    private Set<String> findWriters(ClassOrInterfaceDeclaration type, String fieldName) {
        Set<String> writers = new LinkedHashSet<>();
        for (MethodDeclaration method : type.getMethods()) {
            // Методы запуска (@EventListener(ApplicationReadyEvent.class), CommandLineRunner.run, setApplicationContext)
            // выполняются один раз, пока бин еще не обслуживает запросы
            if (method.isStatic() || method.isSynchronized() || isContainerManaged(method)
                    || CodeContexts.isStartupMethod(method) || isShutdownCallback(method)) {
                continue;
            }

            boolean shadowed = isShadowed(method, fieldName);
            boolean writes = writeTargets(method)
                    .filter(target -> !isInsideSynchronizedBlock(target, method))
                    .filter(target -> !isLazyInitialization(target, fieldName))
                    .anyMatch(target -> isField(target, fieldName, shadowed));
            if (writes) {
                writers.add("'" + method.getNameAsString() + "'");
            }
        }
        return writers;
    }

    // x = ..., x += ..., x++, --x
    private Stream<Expression> writeTargets(MethodDeclaration method) {
        Stream<Expression> assigned = method.findAll(AssignExpr.class).stream().map(AssignExpr::getTarget);
        Stream<Expression> incremented = method.findAll(UnaryExpr.class).stream()
                .filter(unary -> unary.getOperator().name().endsWith("INCREMENT")
                        || unary.getOperator().name().endsWith("DECREMENT"))
                .map(UnaryExpr::getExpression);
        return Stream.concat(assigned, incremented);
    }

    // this.x - всегда поле; просто x - поле, только если в методе нет одноименной переменной или параметра
    private boolean isField(Expression target, String fieldName, boolean shadowed) {
        if (target.isFieldAccessExpr()) {
            return target.asFieldAccessExpr().getScope().isThisExpr()
                    && target.asFieldAccessExpr().getNameAsString().equals(fieldName);
        }
        return !shadowed && target.isNameExpr() && target.asNameExpr().getNameAsString().equals(fieldName);
    }

    private boolean isShadowed(MethodDeclaration method, String fieldName) {
        Stream<String> variables = method.findAll(VariableDeclarator.class).stream()
                .map(NodeWithSimpleName::getNameAsString);
        Stream<String> parameters = method.findAll(Parameter.class).stream()
                .map(NodeWithSimpleName::getNameAsString);
        return Stream.concat(variables, parameters).anyMatch(fieldName::equals);
    }

    // if (cache == null) { cache = ... } - ленивая инициализация: о ней сообщает jr:175, дважды об одном не говорим
    private boolean isLazyInitialization(Expression target, String fieldName) {
        return target.findAncestor(IfStmt.class)
                .filter(branch -> Guards.isNullCheck(branch.getCondition(), fieldName)
                        || branch.getCondition().findAll(BinaryExpr.class).stream()
                        .anyMatch(check -> Guards.isNullCheck(check, fieldName)))
                .isPresent();
    }

    private boolean isInsideSynchronizedBlock(Node node, MethodDeclaration method) {
        Optional<Node> current = node.getParentNode();
        while (current.isPresent() && current.get() != method) {
            if (current.get() instanceof SynchronizedStmt) {
                return true;
            }
            current = current.get().getParentNode();
        }
        return false;
    }

    // Сравниваем по простому имени, чтобы поймать и короткую, и полную запись аннотации
    private Set<String> annotationNames(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .map(annotation -> annotation.getName().getIdentifier())
                .collect(Collectors.toSet());
    }
}
