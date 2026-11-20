package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.loaders.ProjectDeclarations;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.RepositoryEntities;
import ru.akvine.zond.rules.support.StreamChains;
import ru.akvine.zond.rules.support.TestClasses;
import ru.akvine.zond.rules.support.TransactionScopes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Находка строится на уликах, а не на именах: переменная объявлена сущностью JPA из проекта, геттер ведет к полю
 * с ленивой связью-коллекцией, запрос репозитория эту связь заранее не подгружает, а транзакции нет ни у метода,
 * ни выше по цепочке вызовов. {@code page.getContent()} у Page, {@code jobExecution.getStepExecutions()} из Spring
 * Batch и объект из хранилища без JPA под правило не попадают
 */
@Component
public class LazyAccessOutsideTransactionRule extends AbstractRule implements ContextRule {
    // getOrders(), getItems()
    private static final Pattern GETTER = Pattern.compile("^get[A-Z].*");
    private static final int GETTER_PREFIX = 3;

    private static final Set<String> COLLECTION_METHODS =
            Set.of("size", "isEmpty", "stream", "forEach", "iterator", "contains");

    private static final String ENTITY = "Entity";
    // Связи-коллекции: по умолчанию ленивые
    private static final Set<String> LAZY_BY_DEFAULT = Set.of("OneToMany", "ManyToMany", "ElementCollection");
    private static final String EAGER = "EAGER";
    // @LazyCollection(LazyCollectionOption.FALSE), @Fetch(FetchMode.JOIN): связь загружается вместе с сущностью
    private static final Set<String> EAGER_HINTS = Set.of("LazyCollectionOption.FALSE", "FetchMode.JOIN");
    private static final Set<String> HIBERNATE_FETCH = Set.of("LazyCollection", "Fetch");

    // Запрос сам подгружает связи: @EntityGraph либо JOIN FETCH в тексте
    private static final String ENTITY_GRAPH = "EntityGraph";
    private static final String QUERY = "Query";
    private static final String FETCH = "fetch";

    private static final String OPEN_IN_VIEW = "spring.jpa.open-in-view";
    private static final String DISABLED = "false";
    private static final Set<String> CONTROLLERS = Set.of("RestController", "Controller");
    // Код, который выполняется не в HTTP-запросе: сессия Open Session In View до него не дотягивается
    private static final Set<String> OUTSIDE_REQUEST = Set.of(
            "Scheduled", "Schedules", "Async", "KafkaListener", "KafkaHandler", "RabbitListener", "RabbitHandler",
            "JmsListener", "SqsListener", "StreamListener", "PulsarListener", "XxlJob", "PostConstruct");
    private static final int CALLER_DEPTH = 4;

    @Override
    public String code() {
        return RuleCodes.LAZY_ACCESS_OUTSIDE_TRANSACTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет обращение к ленивым коллекциям-связям сущности JPA, загруженной вне транзакции";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        boolean openInViewDisabled = context.configFiles().stream()
                .filter(file -> !file.isNonProduction())
                .anyMatch(file -> file.find(OPEN_IN_VIEW)
                        .filter(property -> DISABLED.equalsIgnoreCase(property.value().trim()))
                        .isPresent());
        return check(context.sources(), openInViewDisabled);
    }

    // Один файл без настроек: так правило вызывают для отдельного класса
    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return check(List.of(sourceFile), false);
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
     * @param graph         граф вызовов проекта
     * @param classes       классы проекта: по ним находится сущность и ее поля
     * @param sessionInView true, если в HTTP-запросе сессия Hibernate остается открытой: приложение веб, а
     *                      spring.jpa.open-in-view не выключено (по умолчанию оно включено)
     */
    private record Project(CallGraph graph, ProjectClasses classes, boolean sessionInView) {
    }

    private List<Violation> check(List<SourceFile> sourceFiles, boolean openInViewDisabled) {
        boolean isWeb = sourceFiles.stream()
                .flatMap(sourceFile -> sourceFile.findAll(ClassOrInterfaceDeclaration.class).stream())
                .anyMatch(type -> Annotations.hasAny(type, CONTROLLERS));
        Project project = new Project(
                CallGraph.of(sourceFiles), ProjectClasses.of(sourceFiles), isWeb && !openInViewDisabled);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            List<Violation> found = check(sourceFile, project);
            found.sort(Comparator.comparingInt(Violation::line));
            violations.addAll(found);
        }
        return violations;
    }

    private List<Violation> check(SourceFile sourceFile, Project project) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration method : sourceFile.findAll(MethodDeclaration.class)) {
            if (method.getBody().isEmpty() || TestClasses.isInside(method)
                    || TransactionScopes.isInTransaction(method.getBody().get(), project.graph())) {
                continue;
            }

            for (VariableDeclarator variable : method.findAll(VariableDeclarator.class)) {
                Set<String> relations = lazyRelationsOf(variable, project.classes());
                if (relations.isEmpty()) {
                    continue;
                }
                String entity = variable.getNameAsString();
                for (Node access : findRelationAccesses(method, entity, relations)) {
                    if (TransactionScopes.enclosingCallback(access).isEmpty()) {
                        violations.add(report(sourceFile, access, describe(access), entity, method, project));
                    }
                }

                // Сущность отдана другому методу, и к связи обращается уже он - тоже без транзакции
                for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                    findAccessInCallee(call, entity, relations, project.graph())
                            .filter(access -> TransactionScopes.enclosingCallback(call).isEmpty())
                            .ifPresent(access -> violations.add(report(sourceFile, call,
                                    access + " в методе '" + call.getNameAsString() + "'", entity, method, project)));
                }
            }
        }
        return violations;
    }

    /**
     * @return имена ленивых связей-коллекций, если переменная - сущность JPA проекта, загруженная запросом
     * репозитория, который сам эти связи не подгружает; иначе пусто
     */
    private Set<String> lazyRelationsOf(VariableDeclarator variable, ProjectClasses classes) {
        Optional<MethodCallExpr> load = variable.getInitializer().flatMap(this::repositoryCall);
        if (load.isEmpty() || isFetchedWithRelations(load.get(), classes)) {
            return Set.of();
        }
        // Order order = ...: тип записан; var order = repository.findById(id).orElseThrow(): сущность репозитория
        Optional<ClassOrInterfaceDeclaration> entity = variable.getType().isClassOrInterfaceType()
                && variable.getType().asClassOrInterfaceType().getTypeArguments().isEmpty()
                && !variable.getType().isVarType()
                ? classes.resolve(variable.getType().asClassOrInterfaceType())
                : load.get().getScope().flatMap(scope -> RepositoryEntities.entityOf(scope, classes));
        return entity.filter(type -> !type.isInterface() && Annotations.has(type, ENTITY))
                .map(this::lazyRelations)
                .orElse(Set.of());
    }

    // Поля самой сущности и ее предков из проекта (@MappedSuperclass)
    private Set<String> lazyRelations(ClassOrInterfaceDeclaration entity) {
        List<TypeDeclaration<?>> types = new ArrayList<>();
        types.add(entity);
        types.addAll(ProjectDeclarations.of(entity).ancestors(entity));
        Set<String> relations = new HashSet<>();
        for (TypeDeclaration<?> type : types) {
            for (FieldDeclaration field : type.getFields()) {
                if (isLazyCollection(field)) {
                    field.getVariables().forEach(variable -> relations.add(variable.getNameAsString()));
                }
            }
        }
        return relations;
    }

    private boolean isLazyCollection(FieldDeclaration field) {
        Optional<AnnotationExpr> relation = Annotations.find(field, LAZY_BY_DEFAULT);
        if (relation.isEmpty() || relation.get().toString().contains(EAGER)) {
            return false;
        }
        return field.getAnnotations().stream()
                .filter(annotation -> HIBERNATE_FETCH.contains(annotation.getName().getIdentifier()))
                .noneMatch(annotation -> EAGER_HINTS.stream().anyMatch(annotation.toString()::contains));
    }

    // repository.findById(id).orElseThrow(), userRepository.getReferenceById(id)
    private Optional<MethodCallExpr> repositoryCall(Expression initializer) {
        Expression value = Nodes.unwrap(initializer);
        if (!value.isMethodCallExpr()) {
            return Optional.empty();
        }
        List<MethodCallExpr> chain = new ArrayList<>();
        chain.add(value.asMethodCallExpr());
        chain.addAll(StreamChains.callsBefore(value.asMethodCallExpr()));
        return chain.stream().filter(Repositories::isRepositoryCall).findFirst();
    }

    // @EntityGraph(attributePaths = "items") либо @Query("select o from Order o join fetch o.items ...")
    private boolean isFetchedWithRelations(MethodCallExpr load, ProjectClasses classes) {
        return load.getScope()
                .flatMap(scope -> RepositoryEntities.repositoryOf(scope, classes))
                .stream()
                .flatMap(declaration -> declaration.getMethodsByName(load.getNameAsString()).stream())
                .anyMatch(query -> Annotations.has(query, ENTITY_GRAPH)
                        || Annotations.find(query, QUERY)
                        .filter(annotation -> annotation.toString().toLowerCase(Locale.ROOT).contains(FETCH))
                        .isPresent());
    }

    // order.getItems().size() и for (Item item : order.getItems())
    private List<Node> findRelationAccesses(Node body, String entity, Set<String> relations) {
        List<Node> accesses = new ArrayList<>();
        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            if (COLLECTION_METHODS.contains(call.getNameAsString())
                    && call.getScope().filter(scope -> isRelationGetter(scope, entity, relations)).isPresent()) {
                accesses.add(call);
            }
        }
        for (ForEachStmt loop : body.findAll(ForEachStmt.class)) {
            if (isRelationGetter(loop.getIterable(), entity, relations)) {
                accesses.add(loop);
            }
        }
        return accesses;
    }

    private String describe(Node access) {
        return access instanceof ForEachStmt loop
                ? loop.getIterable().toString()
                : ((MethodCallExpr) access).getScope().get().toString();
    }

    /**
     * @return обращение к связи в методе проекта, которому сущность передана аргументом
     */
    private Optional<String> findAccessInCallee(
            MethodCallExpr call, String entity, Set<String> relations, CallGraph graph) {
        int index = -1;
        for (int position = 0; position < call.getArguments().size(); position++) {
            if (call.getArgument(position).toString().equals(entity)) {
                index = position;
            }
        }
        if (index < 0) {
            return Optional.empty();
        }
        for (MethodDeclaration target : graph.targetsOf(call)) {
            // В транзакционном методе сущность можно присоединить к сессии заново - о нем не судим
            if (index >= target.getParameters().size() || TransactionScopes.isTransactional(target)) {
                continue;
            }
            String parameter = target.getParameter(index).getNameAsString();
            Optional<String> access = findRelationAccesses(target, parameter, relations).stream()
                    .map(this::describe)
                    .findFirst();
            if (access.isPresent()) {
                return access;
            }
        }
        return Optional.empty();
    }

    private Violation report(SourceFile sourceFile, Node node, String access, String entity, MethodDeclaration method,
                             Project project) {
        String text = "Обращение к связи '" + access + "' вне транзакции: сущность '" + entity + "' получена из"
                + " репозитория в методе без @Transactional, после возврата из репозитория сессия закрыта -"
                + " на LAZY-связи будет LazyInitializationException; загрузите связь заранее"
                + " (JOIN FETCH, @EntityGraph) либо работайте в транзакции";
        if (!project.sessionInView() || runsOutsideRequest(method, project.graph())) {
            return violation(sourceFile, node, text);
        }
        return violation(sourceFile, node, text + ". В HTTP-запросе при включенном " + OPEN_IN_VIEW
                + " (значение по умолчанию) сессия еще открыта и ошибки не будет, поэтому это подозрение")
                .withConfidence(Confidence.SUSPICION);
    }

    // Метод сам либо кто-то выше по цепочке вызовов запускается по расписанию, из очереди или при запуске
    private boolean runsOutsideRequest(MethodDeclaration method, CallGraph graph) {
        Set<MethodDeclaration> reached = Collections.newSetFromMap(new IdentityHashMap<>());
        reached.add(method);
        List<MethodDeclaration> level = List.of(method);
        for (int step = 0; step <= CALLER_DEPTH && !level.isEmpty(); step++) {
            List<MethodDeclaration> callers = new ArrayList<>();
            for (MethodDeclaration current : level) {
                if (Annotations.hasAny(current, OUTSIDE_REQUEST) || CodeContexts.isStartupMethod(current)) {
                    return true;
                }
                graph.callsTo(current).stream()
                        .map(CallGraph.Call::caller)
                        .filter(reached::add)
                        .forEach(callers::add);
            }
            level = callers;
        }
        return false;
    }

    private boolean isRelationGetter(Expression expression, String entity, Set<String> relations) {
        Expression value = Nodes.unwrap(expression);
        if (!value.isMethodCallExpr() || !value.asMethodCallExpr().getArguments().isEmpty()
                || !GETTER.matcher(value.asMethodCallExpr().getNameAsString()).matches()) {
            return false;
        }
        String getter = value.asMethodCallExpr().getNameAsString();
        String property = Character.toLowerCase(getter.charAt(GETTER_PREFIX)) + getter.substring(GETTER_PREFIX + 1);
        return relations.contains(property) && value.asMethodCallExpr().getScope()
                .filter(scope -> scope.isNameExpr() && scope.asNameExpr().getNameAsString().equals(entity))
                .isPresent();
    }
}
