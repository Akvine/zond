package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import ru.akvine.zond.models.SourceFile;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Методы, которые вызываются на каждом элементе данных: из цикла, из лямбды стрима либо ссылкой на метод
 * в поэлементной операции. Что в таком методе дорого само по себе, решает правило.
 */
public final class HotMethods {
    private static final String FIELD_INITIALIZER = "инициализация поля";
    // cache.computeIfAbsent(type, key -> describe(key)): метод вызывается только при промахе кеша
    private static final Set<String> CACHING_CALLS = Set.of("computeIfAbsent", "memoize", "getOrCompute");

    // for (Field field : type.getDeclaredFields()): обход устройства класса, а не данных - он выполняется один раз
    // на класс, сколько бы объектов ни обрабатывалось
    private static final Set<String> STRUCTURE_TYPES = Set.of(
            "Field", "Method", "Constructor", "Parameter", "RecordComponent", "PropertyDescriptor", "Annotation");
    private static final Pattern STRUCTURE_SOURCE = Pattern.compile(
            ".*\\.(getDeclaredFields|getFields|getDeclaredMethods|getMethods|getDeclaredConstructors|getParameters"
                    + "|getRecordComponents|getPropertyDescriptors|getInterfaces|getDeclaredAnnotations"
                    + "|getAnnotations)\\(.*", Pattern.DOTALL);

    private final CallGraph graph;
    private final Map<MethodDeclaration, HotSite> referenced = new IdentityHashMap<>();

    /**
     * @param place откуда метод вызывают на каждом элементе
     * @param exact true, если цель вызова определена однозначно
     */
    public record HotSite(String place, boolean exact) {
    }

    private HotMethods(List<SourceFile> sourceFiles) {
        graph = CallGraph.of(sourceFiles);
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        // items.stream().map(this::normalize), names.forEach(Cleaner::clean)
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodReferenceExpr reference : sourceFile.unit().findAll(MethodReferenceExpr.class)) {
                if (MethodReferences.perElementOperation(reference).isEmpty() || CodeContexts.isStartup(reference)
                        || TestClasses.isInside(reference)) {
                    continue;
                }
                List<MethodDeclaration> targets = MethodReferences.targets(reference, classes);
                for (MethodDeclaration target : targets) {
                    referenced.putIfAbsent(target, new HotSite(
                            reference.findAncestor(MethodDeclaration.class).map(HotMethods::place)
                                    .orElse(FIELD_INITIALIZER),
                            targets.size() == 1));
                }
            }
        }
    }

    public static HotMethods of(List<SourceFile> sourceFiles) {
        return new HotMethods(sourceFiles);
    }

    /**
     * @return место, из которого метод вызывается на каждом элементе; пусто, если такого вызова в проекте нет
     */
    public Optional<HotSite> find(MethodDeclaration method) {
        Optional<HotSite> direct = findDirect(method);
        if (direct.isPresent()) {
            return direct;
        }
        // Метод вызывается один раз из метода, который сам вызывается на каждом элементе
        for (CallGraph.Call call : graph.callsTo(method)) {
            if (call.caller() == method || CodeContexts.isStartup(call.site()) || TestClasses.isInside(call.site())
                    || isCacheLoader(call.site())) {
                continue;
            }
            Optional<HotSite> outer = findDirect(call.caller());
            if (outer.isPresent()) {
                // Вызов внутри такого метода может стоять под условием - на каждом ли элементе он выполняется,
                // по коду не видно
                return Optional.of(new HotSite(outer.get().place() + " → " + place(call.caller()), false));
            }
        }
        return Optional.empty();
    }

    private Optional<HotSite> findDirect(MethodDeclaration method) {
        Optional<HotSite> fromLoop = graph.callsTo(method).stream()
                .filter(call -> call.caller() != method && CodeContexts.isRepeatedOverData(call.site())
                        && !CodeContexts.isStartup(call.site()) && !TestClasses.isInside(call.site())
                        && !isCacheLoader(call.site()) && !isStructureWalk(call.site()))
                .findFirst()
                .map(call -> new HotSite(place(call.caller()), graph.isExact(call.site())));
        return fromLoop.isPresent() ? fromLoop : Optional.ofNullable(referenced.get(method));
    }

    // Ближайший цикл или стрим идет по полям, методам или параметрам класса
    private boolean isStructureWalk(MethodCallExpr site) {
        Optional<Node> iteration = Loops.enclosingIteration(site);
        if (iteration.isEmpty()) {
            return false;
        }
        if (iteration.get() instanceof ForEachStmt loop) {
            boolean overStructure = loop.getVariable().getVariables().stream()
                    .anyMatch(variable -> STRUCTURE_TYPES.contains(LocalTypes.typeName(variable.getType())));
            return overStructure || STRUCTURE_SOURCE.matcher(loop.getIterable().toString()).matches();
        }
        // Arrays.stream(type.getDeclaredFields()).map(field -> describe(field))
        return iteration.get() instanceof LambdaExpr lambda && lambda.getParentNode()
                .filter(parent -> STRUCTURE_SOURCE.matcher(parent.toString()).matches())
                .isPresent();
    }

    private boolean isCacheLoader(MethodCallExpr site) {
        return site.findAncestor(LambdaExpr.class)
                .flatMap(lambda -> lambda.getParentNode())
                .filter(MethodCallExpr.class::isInstance)
                .map(MethodCallExpr.class::cast)
                .filter(call -> CACHING_CALLS.contains(call.getNameAsString()))
                .isPresent();
    }

    private static String place(MethodDeclaration caller) {
        Optional<String> owner = caller.findAncestor(ClassOrInterfaceDeclaration.class)
                .map(ClassOrInterfaceDeclaration::getNameAsString);
        if (owner.isEmpty()) {
            owner = caller.findAncestor(TypeDeclaration.class).map(type -> ((TypeDeclaration<?>) type).getNameAsString());
        }
        return owner.map(name -> name + ".").orElse("") + caller.getNameAsString();
    }
}
