package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class CheckMutableStateInSingletonBeanRule implements Rule {
    private static final Set<String> BEAN_ANNOTATIONS =
            Set.of("Component", "Service", "Repository", "Controller", "RestController");

    // Бин не singleton: у каждого потребителя / запроса / сессии свой экземпляр
    private static final Set<String> SCOPE_ANNOTATIONS = Set.of("RequestScope", "SessionScope");
    private static final String SCOPE = "Scope";
    private static final List<String> NOT_SINGLETON_SCOPES = List.of("prototype", "request", "session");

    // Сеттеры такого класса вызывает Spring при старте, а не прикладной код
    private static final String CONFIGURATION_PROPERTIES = "ConfigurationProperties";

    // Поле или метод, которыми управляет контейнер: значение задается один раз при создании бина
    private static final Set<String> CONTAINER_MANAGED_ANNOTATIONS = Set.of(
            "Autowired", "Inject", "Resource", "Value", "PersistenceContext", "PersistenceUnit", "PostConstruct");

    @Override
    public String name() {
        return getClass().getSimpleName();
    }

    @Override
    public String code() {
        return RuleCodes.CHECK_MUTABLE_STATE_IN_SINGLETON_BEAN_RULE_CODE;
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
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
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
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CONCURRENCY;
    }

    private boolean isSingletonBean(ClassOrInterfaceDeclaration type) {
        Set<String> annotations = annotationNames(type);
        if (annotations.stream().noneMatch(BEAN_ANNOTATIONS::contains)
                || annotations.contains(CONFIGURATION_PROPERTIES)
                || annotations.stream().anyMatch(SCOPE_ANNOTATIONS::contains)) {
            return false;
        }

        // @Scope("prototype"), @Scope(SCOPE_PROTOTYPE), @Scope(value = WebApplicationContext.SCOPE_REQUEST) и т.п.
        return type.getAnnotations().stream()
                .filter(annotation -> SCOPE.equals(annotation.getName().getIdentifier()))
                .map(annotation -> annotation.toString().toLowerCase())
                .noneMatch(scope -> NOT_SINGLETON_SCOPES.stream().anyMatch(scope::contains));
    }

    private boolean isContainerManaged(NodeWithAnnotations<?> node) {
        return annotationNames(node).stream().anyMatch(CONTAINER_MANAGED_ANNOTATIONS::contains);
    }

    /**
     * @return имена методов, в которых полю присваивается значение; конструкторы сюда не попадают
     */
    private Set<String> findWriters(ClassOrInterfaceDeclaration type, String fieldName) {
        Set<String> writers = new LinkedHashSet<>();
        for (MethodDeclaration method : type.getMethods()) {
            if (method.isStatic() || method.isSynchronized() || isContainerManaged(method)) {
                continue;
            }

            boolean shadowed = isShadowed(method, fieldName);
            boolean writes = writeTargets(method)
                    .filter(target -> !isInsideSynchronizedBlock(target, method))
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
