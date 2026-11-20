package ru.akvine.zond.loaders;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.DataKey;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithExtends;
import com.github.javaparser.ast.nodeTypes.NodeWithImplements;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Объявления типов проекта по простому имени. По записи в коде находит то, на что она указывает, без решателя
 * типов: составную аннотацию ({@code @UseCase} несет {@code @Component}), предка класса, реализованный интерфейс.
 * <p>
 * Имя сопоставляется с объявлением строго: тот же файл, тот же пакет либо импорт. Одноименный тип другого
 * модуля, до которого из файла не дотянуться, не подходит.
 */
public final class ProjectDeclarations {
    /**
     * Общий для всех файлов проекта указатель: загрузчик прикрепляет его к каждому файлу
     */
    public static final DataKey<ProjectDeclarations> KEY = new DataKey<>() {
    };

    // Свойства самого объявления аннотации: на помеченный ею код они не переходят
    private static final Set<String> SELF_ONLY = Set.of(
            "Target", "Retention", "Documented", "Inherited", "Repeatable", "Deprecated", "SuppressWarnings",
            "Generated", "FunctionalInterface", "API", "Beta", "Experimental", "Incubating", "Internal");
    // @A несет @B, @B несет @C: глубже составные аннотации не пишут, а предел защищает от замкнутой цепочки
    private static final int MAX_META_DEPTH = 4;
    private static final int MAX_ANCESTORS = 30;
    private static final String PACKAGE_SEPARATOR = ".";

    private final Map<String, List<TypeDeclaration<?>>> byName = new HashMap<>();
    private final Set<String> annotationNames = new HashSet<>();
    private final Map<AnnotationDeclaration, List<AnnotationExpr>> metaAnnotations = new IdentityHashMap<>();
    private final Map<TypeDeclaration<?>, List<TypeDeclaration<?>>> knownAncestors = new IdentityHashMap<>();

    private ProjectDeclarations() {
    }

    public static ProjectDeclarations of(List<CompilationUnit> units) {
        ProjectDeclarations declarations = new ProjectDeclarations();
        for (CompilationUnit unit : units) {
            for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
                declarations.byName.computeIfAbsent(type.getNameAsString(), name -> new ArrayList<>()).add(type);
                if (type instanceof AnnotationDeclaration) {
                    declarations.annotationNames.add(type.getNameAsString());
                }
            }
        }
        return declarations;
    }

    /**
     * @return указатель проекта, к которому относится узел; если файл разобран отдельно (без загрузчика) -
     * указатель по самому файлу
     */
    public static ProjectDeclarations of(Node node) {
        Optional<CompilationUnit> found = node.findCompilationUnit();
        if (found.isEmpty()) {
            return new ProjectDeclarations();
        }
        CompilationUnit unit = found.get();
        if (unit.containsData(KEY)) {
            return unit.getData(KEY);
        }
        synchronized (unit) {
            if (!unit.containsData(KEY)) {
                unit.setData(KEY, of(List.of(unit)));
            }
            return unit.getData(KEY);
        }
    }

    /**
     * @return true, если в проекте объявлена аннотация с таким простым именем
     */
    public boolean declaresAnnotation(String simpleName) {
        return annotationNames.contains(simpleName);
    }

    /**
     * @param name  имя, как оно записано в коде: простое, с внешним классом либо полное
     * @param usage место, где оно записано
     * @return объявление, на которое имя указывает из этого места
     */
    public Optional<TypeDeclaration<?>> find(String name, Node usage) {
        String simpleName = name.substring(name.lastIndexOf(PACKAGE_SEPARATOR) + 1);
        List<TypeDeclaration<?>> candidates = byName.getOrDefault(simpleName, List.of());
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Optional<CompilationUnit> unit = usage.findCompilationUnit();
        TypeDeclaration<?> best = null;
        int bestRank = Integer.MAX_VALUE;
        for (TypeDeclaration<?> candidate : candidates) {
            int rank = rank(candidate, name, unit.orElse(null));
            if (rank < bestRank) {
                best = candidate;
                bestRank = rank;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * @return объявление аннотации, если она объявлена в проекте
     */
    public Optional<AnnotationDeclaration> annotation(AnnotationExpr usage) {
        if (!annotationNames.contains(usage.getName().getIdentifier())) {
            return Optional.empty();
        }
        return find(usage.getNameAsString(), usage)
                .filter(type -> type instanceof AnnotationDeclaration)
                .map(type -> (AnnotationDeclaration) type);
    }

    /**
     * @return аннотации, которые несет составная аннотация проекта, вместе с теми, что несут они сами:
     * {@code @TransactionalService} -> {@code @Service}, {@code @Transactional(readOnly = true)}. Для аннотации
     * из библиотеки - пусто: ее объявления в исходниках нет
     */
    public List<AnnotationExpr> metaAnnotations(AnnotationExpr usage) {
        Optional<AnnotationDeclaration> declaration = annotation(usage);
        if (declaration.isEmpty()) {
            return List.of();
        }
        synchronized (metaAnnotations) {
            List<AnnotationExpr> known = metaAnnotations.get(declaration.get());
            if (known == null) {
                known = new ArrayList<>();
                collectMeta(declaration.get(), MAX_META_DEPTH, new HashSet<>(), known);
                metaAnnotations.put(declaration.get(), List.copyOf(known));
            }
            return known;
        }
    }

    /**
     * @return предки типа, объявленные в проекте: классы из extends и интерфейсы из implements, вплоть до корня
     */
    public List<TypeDeclaration<?>> ancestors(TypeDeclaration<?> type) {
        synchronized (knownAncestors) {
            return knownAncestors.computeIfAbsent(type, this::findAncestors);
        }
    }

    private List<TypeDeclaration<?>> findAncestors(TypeDeclaration<?> type) {
        Set<TypeDeclaration<?>> ancestors = new LinkedHashSet<>();
        List<TypeDeclaration<?>> queue = new ArrayList<>(parents(type));
        for (int index = 0; index < queue.size() && ancestors.size() < MAX_ANCESTORS; index++) {
            TypeDeclaration<?> parent = queue.get(index);
            if (parent != type && ancestors.add(parent)) {
                queue.addAll(parents(parent));
            }
        }
        return List.copyOf(ancestors);
    }

    /**
     * @return типы проекта, записанные у этого типа в extends и implements
     */
    public List<TypeDeclaration<?>> parents(TypeDeclaration<?> type) {
        List<ClassOrInterfaceType> written = new ArrayList<>();
        if (type instanceof NodeWithExtends<?> withExtends) {
            written.addAll(withExtends.getExtendedTypes());
        }
        if (type instanceof NodeWithImplements<?> withImplements) {
            written.addAll(withImplements.getImplementedTypes());
        }
        List<TypeDeclaration<?>> parents = new ArrayList<>();
        for (ClassOrInterfaceType parent : written) {
            find(parent.getNameWithScope(), type).ifPresent(parents::add);
        }
        return parents;
    }

    private void collectMeta(AnnotationDeclaration declaration, int depth, Set<AnnotationDeclaration> visited,
                             List<AnnotationExpr> result) {
        if (depth == 0 || !visited.add(declaration)) {
            return;
        }
        for (AnnotationExpr meta : declaration.getAnnotations()) {
            if (SELF_ONLY.contains(meta.getName().getIdentifier())) {
                continue;
            }
            result.add(meta);
            annotation(meta).ifPresent(nested -> collectMeta(nested, depth - 1, visited, result));
        }
    }

    // Чем меньше, тем точнее имя указывает на объявление; Integer.MAX_VALUE - не указывает вовсе
    private int rank(TypeDeclaration<?> candidate, String name, CompilationUnit unit) {
        CompilationUnit declaredIn = candidate.findCompilationUnit().orElse(null);
        if (unit == null || declaredIn == null) {
            return Integer.MAX_VALUE;
        }
        // У класса, объявленного внутри метода, полного имени нет: он виден только в своем файле
        Optional<String> known = candidate.getFullyQualifiedName();
        if (known.isEmpty()) {
            return unit == declaredIn ? 1 : Integer.MAX_VALUE;
        }
        String qualifiedName = known.get();
        boolean isQualified = name.contains(PACKAGE_SEPARATOR);
        if (isQualified && qualifiedName.equals(name)) {
            return 0;
        }
        if (isQualified && !qualifiedName.endsWith(PACKAGE_SEPARATOR + name)) {
            return Integer.MAX_VALUE;
        }
        if (unit == declaredIn) {
            return 1;
        }
        // Outer.Inner: импортирован либо лежит рядом внешний класс
        String imported = isQualified
                ? qualifiedName.substring(0, qualifiedName.length() - name.length()) + firstPart(name)
                : qualifiedName;
        String owner = imported.contains(PACKAGE_SEPARATOR)
                ? imported.substring(0, imported.lastIndexOf(PACKAGE_SEPARATOR))
                : "";
        boolean wildcard = false;
        for (ImportDeclaration declaration : unit.getImports()) {
            if (declaration.isStatic()) {
                continue;
            }
            if (!declaration.isAsterisk() && declaration.getNameAsString().equals(imported)) {
                return 2;
            }
            wildcard |= declaration.isAsterisk() && declaration.getNameAsString().equals(owner);
        }
        if (packageOf(unit).equals(owner)) {
            return 3;
        }
        return wildcard ? 4 : Integer.MAX_VALUE;
    }

    private String firstPart(String name) {
        int end = name.indexOf(PACKAGE_SEPARATOR);
        return end < 0 ? name : name.substring(0, end);
    }

    private String packageOf(CompilationUnit unit) {
        return unit.getPackageDeclaration().map(declaration -> declaration.getNameAsString()).orElse("");
    }
}
