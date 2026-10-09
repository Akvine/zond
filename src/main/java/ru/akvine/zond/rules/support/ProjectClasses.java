package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import ru.akvine.zond.models.SourceFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Классы проекта по простым именам: по ним правила находят объявление типа, его предков, наследников и поля.
 * Имя, которое в проекте носят несколько классов, считается неизвестным - угадывать между ними нельзя.
 */
public final class ProjectClasses {
    private static final String OBJECT = "Object";

    /**
     * Поля класса вместе с полями предков
     *
     * @param types    имя поля -> объявленный тип поля
     * @param complete false, если часть предков лежит вне проекта: тогда полей может быть больше, чем видно
     */
    public record Properties(Map<String, FieldType> types, boolean complete) {

        public boolean has(String name) {
            return types.containsKey(name);
        }
    }

    /**
     * Тип поля: простое имя и, для коллекций, имя типа элемента
     */
    public record FieldType(String name, String elementName) {
    }

    private final Map<String, List<ClassOrInterfaceDeclaration>> bySimpleName = new HashMap<>();
    private final Map<ClassOrInterfaceDeclaration, SourceFile> files = new HashMap<>();

    private ProjectClasses() {
    }

    public static ProjectClasses of(List<SourceFile> sourceFiles) {
        ProjectClasses classes = new ProjectClasses();
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                classes.bySimpleName.computeIfAbsent(type.getNameAsString(), name -> new ArrayList<>()).add(type);
                classes.files.put(type, sourceFile);
            }
        }
        return classes;
    }

    public Optional<ClassOrInterfaceDeclaration> find(String simpleName) {
        List<ClassOrInterfaceDeclaration> found = bySimpleName.getOrDefault(simpleName, List.of());
        return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
    }

    /**
     * Класс проекта, на который ссылается тип в коде. В отличие от поиска по простому имени учитывает импорты
     * и пакет файла: одноименные классы проекта различаются, а класс библиотеки не подменяется классом проекта
     * с таким же именем.
     *
     * @return пусто, если тип объявлен вне проекта либо понять, какой из классов имеется в виду, нельзя
     */
    public Optional<ClassOrInterfaceDeclaration> resolve(ClassOrInterfaceType usage) {
        String simpleName = usage.getNameAsString();
        List<ClassOrInterfaceDeclaration> candidates = bySimpleName.getOrDefault(simpleName, List.of());
        Optional<CompilationUnit> unit = usage.findCompilationUnit();
        if (candidates.isEmpty() || unit.isEmpty()) {
            return candidates.isEmpty() ? Optional.empty() : find(simpleName);
        }

        // Outer.Inner либо полное имя прямо в коде
        if (usage.getScope().isPresent()) {
            String qualified = usage.getScope().get().asString() + "." + simpleName;
            return only(candidates.stream()
                    .filter(candidate -> qualifiedName(candidate).equals(qualified)
                            || qualifiedName(candidate).endsWith("." + qualified))
                    .toList());
        }

        // import ru.shop.dto.Order: имя задано однозначно, и если такого класса в проекте нет - он из библиотеки
        Optional<String> imported = unit.get().getImports().stream()
                .filter(declaration -> !declaration.isAsterisk() && !declaration.isStatic())
                .map(declaration -> declaration.getNameAsString())
                .filter(name -> name.equals(simpleName) || name.endsWith("." + simpleName))
                .findFirst();
        if (imported.isPresent()) {
            return only(candidates.stream().filter(candidate -> qualifiedName(candidate).equals(imported.get())).toList());
        }

        // Класс того же пакета
        String ownPackage = unit.get().getPackageDeclaration().map(declaration -> declaration.getNameAsString() + ".").orElse("");
        Optional<ClassOrInterfaceDeclaration> neighbour = only(candidates.stream()
                .filter(candidate -> qualifiedName(candidate).equals(ownPackage + simpleName))
                .toList());
        if (neighbour.isPresent()) {
            return neighbour;
        }
        // Вложенный класс из того же файла
        Optional<ClassOrInterfaceDeclaration> nested = only(candidates.stream()
                .filter(candidate -> candidate.findCompilationUnit().filter(found -> found == unit.get()).isPresent())
                .toList());
        if (nested.isPresent()) {
            return nested;
        }
        // import ru.shop.dto.*
        Set<String> packages = new HashSet<>();
        unit.get().getImports().stream()
                .filter(declaration -> declaration.isAsterisk() && !declaration.isStatic())
                .forEach(declaration -> packages.add(declaration.getNameAsString() + "." + simpleName));
        return only(candidates.stream().filter(candidate -> packages.contains(qualifiedName(candidate))).toList());
    }

    private Optional<ClassOrInterfaceDeclaration> only(List<ClassOrInterfaceDeclaration> found) {
        return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
    }

    private String qualifiedName(ClassOrInterfaceDeclaration type) {
        return type.getFullyQualifiedName().orElse(type.getNameAsString());
    }

    /**
     * @return true, если класс с таким простым именем объявлен в проекте, пусть даже не один
     */
    public boolean isDeclared(String simpleName) {
        return bySimpleName.containsKey(simpleName);
    }

    public List<ClassOrInterfaceDeclaration> all() {
        return bySimpleName.values().stream().flatMap(List::stream).toList();
    }

    public SourceFile fileOf(ClassOrInterfaceDeclaration type) {
        return files.get(type);
    }

    /**
     * @return класс-предок из проекта либо пусто: предка нет или он лежит вне проекта
     */
    public Optional<ClassOrInterfaceDeclaration> parent(ClassOrInterfaceDeclaration type) {
        return type.getExtendedTypes().stream()
                .findFirst()
                .flatMap(this::resolve)
                .filter(parent -> !parent.isInterface());
    }

    /**
     * @return true, если у класса есть предок, которого в проекте нет: о его полях и методах ничего не известно
     */
    public boolean hasUnknownAncestor(ClassOrInterfaceDeclaration type) {
        Set<ClassOrInterfaceDeclaration> seen = new HashSet<>();
        ClassOrInterfaceDeclaration current = type;
        while (current != null && seen.add(current)) {
            if (current.isInterface() || current.getExtendedTypes().isEmpty()) {
                return false;
            }
            ClassOrInterfaceType extended = current.getExtendedTypes().get(0);
            if (OBJECT.equals(extended.getNameAsString())) {
                return false;
            }
            Optional<ClassOrInterfaceDeclaration> parent = resolve(extended);
            if (parent.isEmpty()) {
                return true;
            }
            current = parent.get();
        }
        return false;
    }

    /**
     * @return все наследники класса в проекте, прямые и через несколько уровней
     */
    public List<ClassOrInterfaceDeclaration> subclasses(ClassOrInterfaceDeclaration type) {
        List<ClassOrInterfaceDeclaration> found = new ArrayList<>();
        Set<ClassOrInterfaceDeclaration> seen = new HashSet<>(Set.of(type));
        List<ClassOrInterfaceDeclaration> queue = new ArrayList<>(List.of(type));
        while (!queue.isEmpty()) {
            ClassOrInterfaceDeclaration current = queue.remove(0);
            for (ClassOrInterfaceDeclaration candidate : all()) {
                boolean extendsCurrent = !candidate.isInterface() && parent(candidate).filter(current::equals).isPresent();
                if (extendsCurrent && seen.add(candidate)) {
                    found.add(candidate);
                    queue.add(candidate);
                }
            }
        }
        return found;
    }

    /**
     * @return поля класса и его предков из проекта, кроме статических
     */
    public Properties properties(ClassOrInterfaceDeclaration type) {
        Map<String, FieldType> types = new LinkedHashMap<>();
        Set<ClassOrInterfaceDeclaration> seen = new HashSet<>();
        ClassOrInterfaceDeclaration current = type;
        while (current != null && seen.add(current)) {
            for (FieldDeclaration field : current.getFields()) {
                if (field.isStatic()) {
                    continue;
                }
                for (VariableDeclarator variable : field.getVariables()) {
                    types.putIfAbsent(variable.getNameAsString(), typeOf(variable));
                }
            }
            current = parent(current).orElse(null);
        }
        return new Properties(types, !hasUnknownAncestor(type));
    }

    // List<OrderItem> -> List и OrderItem; Customer -> Customer и null
    private FieldType typeOf(VariableDeclarator variable) {
        if (!variable.getType().isClassOrInterfaceType()) {
            return new FieldType(variable.getType().asString(), null);
        }
        ClassOrInterfaceType type = variable.getType().asClassOrInterfaceType();
        String element = type.getTypeArguments()
                .filter(arguments -> !arguments.isEmpty())
                .map(arguments -> arguments.get(arguments.size() - 1))
                .filter(argument -> argument.isClassOrInterfaceType())
                .map(argument -> argument.asClassOrInterfaceType().getNameAsString())
                .orElse(null);
        return new FieldType(type.getNameAsString(), element);
    }
}
