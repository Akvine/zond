package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithArguments;
import com.github.javaparser.ast.stmt.ForEachStmt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loops;
import ru.akvine.zond.rules.support.MethodReferences;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * orders.stream().map(OrderModel::new), а конструктор OrderModel читает order.getClient().getName() у LAZY-связи:
 * список загружен одним запросом, но при сборке DTO за связью каждого элемента уходит еще один - N+1, спрятанный
 * в конвертере.
 */
@Component
public class LazyRelationInMappingRule extends AbstractContextRule {
    private static final Set<String> ENTITY_ANNOTATIONS = Set.of("Entity", "MappedSuperclass");
    // Коллекции ленивы всегда, пока не сказано обратное
    private static final Set<String> COLLECTION_RELATIONS = Set.of("OneToMany", "ManyToMany", "ElementCollection");
    // Ссылки ленивы, только если это указано явно
    private static final Set<String> REFERENCE_RELATIONS = Set.of("ManyToOne", "OneToOne");
    // @BatchSize и @Fetch(SUBSELECT) загружают связь пачкой - отдельного запроса на элемент уже нет
    private static final Set<String> BATCH_ANNOTATIONS = Set.of("BatchSize", "Fetch");
    private static final String JMIX_ENTITY = "JmixEntity";
    private static final String LAZY = "LAZY";
    private static final String EAGER = "EAGER";
    private static final String BATCH_FETCH_SIZE = "default_batch_fetch_size";
    private static final String NEW = "new";
    // order.getClient().getId() прокси не раскрывает: идентификатор в нем уже есть
    private static final Set<String> PROXY_SAFE = Set.of("getId", "getClass");
    private static final Set<String> NULL_CHECKS = Set.of("isNull", "nonNull", "requireNonNull", "isInitialized");
    private static final Set<String> FETCH_ANNOTATIONS = Set.of("EntityGraph", "NamedEntityGraph", "NamedAttributeNode");
    private static final Pattern JOIN_FETCH = Pattern.compile("(?i)join\\s+fetch\\s+\\w+\\.(\\w+)");
    private static final Pattern QUOTED_NAME = Pattern.compile("\"(\\w+)(\\.\\w+)*\"");
    private static final int PASSES = 2;

    // Связь сущности, которая загружается при первом обращении
    private record Relation(String entity, String field) {
    }

    private record Project(Map<String, Map<String, String>> lazy, ProjectClasses classes, CallGraph graph) {
    }

    @Override
    public String code() {
        return RuleCodes.LAZY_RELATION_IN_MAPPING_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет преобразование списка сущностей в DTO, при котором конвертер читает LAZY-связь"
                + " каждого элемента";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        // hibernate.default_batch_fetch_size: связи догружаются пачками, запроса на каждый элемент нет
        boolean batchFetching = context.configFiles().stream()
                .flatMap(config -> config.properties().stream())
                .anyMatch(property -> property.key().contains(BATCH_FETCH_SIZE));
        List<SourceFile> sources = context.sources();
        ProjectClasses classes = ProjectClasses.of(sources);
        Map<String, Map<String, String>> lazy = lazyRelations(classes);
        if (batchFetching || lazy.isEmpty()) {
            return List.of();
        }
        Project project = new Project(lazy, classes, CallGraph.of(sources));
        Map<Node, Relation> readers = findReaders(sources, project);
        Set<String> fetched = fetchedSomewhere(sources);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sources) {
            Set<Integer> reportedLines = new HashSet<>();
            for (Node site : sourceFile.unit().findAll(Node.class)) {
                Optional<Relation> relation = perElementRead(site, readers, project);
                if (relation.isEmpty() || TestClasses.isInside(site)
                        || !reportedLines.add(site.getBegin().map(position -> position.line).orElse(0))) {
                    continue;
                }
                // Где-то в проекте эту связь уже загружают через JOIN FETCH или @EntityGraph - возможно, и здесь
                boolean maybeFetched = fetched.contains(relation.get().field());
                violations.add(violation(sourceFile, site,
                        "'" + shorten(site) + "' для каждого элемента читает LAZY-связь '" + relation.get().entity()
                                + "." + relation.get().field() + "': список загружен одним запросом, а за связью"
                                + " каждого элемента уйдет отдельный (N+1); загрузите ее вместе со списком - JOIN FETCH"
                                + " или @EntityGraph в запросе - либо задайте связи @BatchSize")
                        .withConfidence(maybeFetched ? Confidence.SUSPICION : Confidence.PROBABLE));
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
        return ErrorType.PERFORMANCE;
    }

    // Каким запросом загружен список, по месту преобразования видно не всегда
    @Override
    public Confidence confidence() {
        return Confidence.PROBABLE;
    }

    // Сущность -> геттер ленивой связи -> имя поля
    private Map<String, Map<String, String>> lazyRelations(ProjectClasses classes) {
        Map<String, Map<String, String>> lazy = new HashMap<>();
        for (ClassOrInterfaceDeclaration type : classes.all()) {
            // Сущности Jmix загружают по планам выборки - о них говорят jr:387 и jr:388
            if (!Annotations.hasAny(type, ENTITY_ANNOTATIONS) || Annotations.has(type, JMIX_ENTITY)) {
                continue;
            }
            for (FieldDeclaration field : type.getFields()) {
                if (isLazy(field)) {
                    String name = field.getVariable(0).getNameAsString();
                    lazy.computeIfAbsent(type.getNameAsString(), key -> new HashMap<>())
                            .put("get" + StringUtils.capitalize(name), name);
                }
            }
        }
        return lazy;
    }

    private boolean isLazy(FieldDeclaration field) {
        if (field.isStatic() || Annotations.hasAny(field, BATCH_ANNOTATIONS)) {
            return false;
        }
        Optional<AnnotationExpr> collection = Annotations.find(field, COLLECTION_RELATIONS);
        if (collection.isPresent()) {
            return !collection.get().toString().contains(EAGER);
        }
        return Annotations.find(field, REFERENCE_RELATIONS)
                .filter(reference -> reference.toString().contains(LAZY))
                .isPresent();
    }

    // Методы и конструкторы, которые читают ленивую связь своего параметра-сущности - сами либо через вызов
    private Map<Node, Relation> findReaders(List<SourceFile> sources, Project project) {
        List<CallableDeclaration<?>> callables = new ArrayList<>();
        for (SourceFile source : sources) {
            source.unit().findAll(CallableDeclaration.class).stream()
                    .filter(callable -> !TestClasses.isInside(callable))
                    .forEach(callables::add);
        }
        Map<Node, Relation> readers = new IdentityHashMap<>();
        for (CallableDeclaration<?> callable : callables) {
            directRead(callable, project.lazy()).ifPresent(relation -> readers.put(callable, relation));
        }
        // toDto(order) -> fill(dto, order) -> order.getItems(): связь читает метод, которому передали сущность
        for (int pass = 0; pass < PASSES; pass++) {
            Map<Node, Relation> found = new IdentityHashMap<>();
            for (CallableDeclaration<?> callable : callables) {
                passedRead(callable, readers, project).ifPresent(relation -> found.put(callable, relation));
            }
            found.forEach(readers::putIfAbsent);
        }
        return readers;
    }

    private Optional<Relation> directRead(CallableDeclaration<?> callable, Map<String, Map<String, String>> lazy) {
        for (Parameter parameter : callable.getParameters()) {
            String entity = LocalTypes.typeName(parameter.getType());
            Map<String, String> getters = lazy.get(entity);
            if (getters == null) {
                continue;
            }
            Optional<Relation> relation = callable.findAll(MethodCallExpr.class).stream()
                    .filter(call -> isCallOn(call, parameter.getNameAsString()) && isLoaded(call))
                    .map(call -> getters.get(call.getNameAsString()))
                    .filter(field -> field != null)
                    .map(field -> new Relation(entity, field))
                    .findFirst();
            if (relation.isPresent()) {
                return relation;
            }
        }
        return Optional.empty();
    }

    private Optional<Relation> passedRead(CallableDeclaration<?> callable, Map<Node, Relation> readers, Project project) {
        Set<String> entityParameters = new HashSet<>();
        for (Parameter parameter : callable.getParameters()) {
            if (project.lazy().containsKey(LocalTypes.typeName(parameter.getType()))) {
                entityParameters.add(parameter.getNameAsString());
            }
        }
        if (entityParameters.isEmpty()) {
            return Optional.empty();
        }
        for (Node node : callable.findAll(Node.class)) {
            boolean passesEntity = node instanceof NodeWithArguments<?> call && call.getArguments().stream()
                    .anyMatch(argument -> argument.isNameExpr() && entityParameters.contains(argument.toString()));
            Optional<Relation> relation = passesEntity ? readBy(node, readers, project) : Optional.empty();
            if (relation.isPresent()) {
                return relation;
            }
        }
        return Optional.empty();
    }

    // Связь, которую читает вызванный метод или конструктор
    private Optional<Relation> readBy(Node call, Map<Node, Relation> readers, Project project) {
        List<? extends Node> targets = List.of();
        if (call instanceof MethodCallExpr method) {
            targets = project.graph().targetsOf(method);
        } else if (call instanceof ObjectCreationExpr creation) {
            targets = constructorsOf(creation.getType().getNameAsString(), project.classes());
        }
        return targets.stream().map(readers::get).filter(relation -> relation != null).findFirst();
    }

    private List<? extends Node> constructorsOf(String type, ProjectClasses classes) {
        return classes.find(type).map(ClassOrInterfaceDeclaration::getConstructors).orElse(List.of());
    }

    private boolean isCallOn(MethodCallExpr call, String variable) {
        return call.getArguments().isEmpty()
                && call.getScope().map(Nodes::unwrap).filter(scope -> scope.toString().equals(variable)).isPresent();
    }

    /**
     * Связь раскрывается, когда у нее что-то спрашивают (кроме идентификатора), обходят ее или отдают дальше -
     * в конвертер или конструктор. Сравнение с null и простое присваивание прокси не трогают
     */
    private boolean isLoaded(MethodCallExpr getter) {
        Node parent = getter.getParentNode().orElse(null);
        if (parent instanceof MethodCallExpr user && user.getScope().filter(scope -> scope == getter).isPresent()) {
            return !PROXY_SAFE.contains(user.getNameAsString());
        }
        if (parent instanceof MethodCallExpr receiver) {
            return !NULL_CHECKS.contains(receiver.getNameAsString());
        }
        return parent instanceof ObjectCreationExpr
                || parent instanceof ForEachStmt loop && loop.getIterable() == getter;
    }

    // Место, которое выполняется для каждого элемента списка и читает ленивую связь
    private Optional<Relation> perElementRead(Node site, Map<Node, Relation> readers, Project project) {
        if (site instanceof MethodReferenceExpr reference) {
            if (MethodReferences.perElementOperation(reference).isEmpty()) {
                return Optional.empty();
            }
            List<? extends Node> targets = NEW.equals(reference.getIdentifier())
                    ? MethodReferences.scopeType(reference)
                    .map(type -> constructorsOf(type, project.classes()))
                    .orElse(List.of())
                    : MethodReferences.targets(reference, project.classes());
            return targets.stream().map(readers::get).filter(relation -> relation != null).findFirst();
        }
        boolean call = site instanceof MethodCallExpr || site instanceof ObjectCreationExpr;
        if (!call || !CodeContexts.isRepeatedOverData(site)) {
            return Optional.empty();
        }
        Optional<Relation> viaCallee = readBy(site, readers, project);
        return viaCallee.isPresent() || !(site instanceof MethodCallExpr getter)
                ? viaCallee
                : readOfLoopElement(getter, project.lazy());
    }

    // for (OrderEntity order : orders) { ... order.getClient().getName() ... }: связь читают прямо в цикле
    private Optional<Relation> readOfLoopElement(MethodCallExpr getter, Map<String, Map<String, String>> lazy) {
        Optional<Expression> element = getter.getScope().map(Nodes::unwrap).filter(Expression::isNameExpr);
        Optional<Node> declaration = element.flatMap(LocalTypes::findDeclaration);
        Optional<Node> iteration = Loops.enclosingIteration(getter);
        if (!getter.getArguments().isEmpty() || declaration.isEmpty() || iteration.isEmpty()
                || Loops.isDeclaredOutside(iteration.get(), declaration.get()) || !isLoaded(getter)) {
            return Optional.empty();
        }
        return LocalTypes.declaredType(declaration.get())
                .filter(lazy::containsKey)
                .flatMap(entity -> Optional.ofNullable(lazy.get(entity).get(getter.getNameAsString()))
                        .map(field -> new Relation(entity, field)));
    }

    // Связи, которые хоть один запрос проекта загружает заранее: join fetch o.items, attributePaths = "items"
    private Set<String> fetchedSomewhere(List<SourceFile> sources) {
        Set<String> fetched = new HashSet<>();
        for (SourceFile source : sources) {
            for (AnnotationExpr annotation : source.unit().findAll(AnnotationExpr.class)) {
                String text = annotation.toString();
                Matcher join = JOIN_FETCH.matcher(text);
                while (join.find()) {
                    fetched.add(join.group(1));
                }
                if (FETCH_ANNOTATIONS.contains(annotation.getName().getIdentifier())) {
                    Matcher name = QUOTED_NAME.matcher(text);
                    while (name.find()) {
                        fetched.add(name.group(1));
                    }
                }
            }
        }
        return fetched;
    }

    private String shorten(Node site) {
        if (site instanceof MethodCallExpr call) {
            return call.getNameAsString() + "(...)";
        }
        if (site instanceof ObjectCreationExpr creation) {
            return "new " + creation.getType().getNameAsString() + "(...)";
        }
        return Nodes.text(site);
    }
}
