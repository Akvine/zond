package ru.akvine.zond.rules;

import com.github.javaparser.ast.DataKey;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithExtends;
import com.github.javaparser.ast.nodeTypes.NodeWithImplements;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Разрешение типов: настоящий тип выражения с учетом других файлов проекта, JDK и подключенных библиотек.
 * Работает, только если файл загружен вместе с решателем типов; тип, который разрешить не удалось
 * (нет библиотеки, код сгенерирован при сборке), считается неизвестным - исключения наружу не выходят.
 * <p>
 * Проверки возвращают Optional: пусто значит "по типу сказать нельзя", и правило решает по именам, как раньше.
 */
@UtilityClass
class Types {
    private static final DataKey<Optional<ResolvedType>> RESOLVED_TYPE = new DataKey<>() {
    };
    private static final DataKey<Optional<ResolvedType>> RESOLVED_TYPE_NAME = new DataKey<>() {
    };

    private static final String JDK_PACKAGE = "java.";
    private static final String ARRAY_SUFFIX = "[]";
    private static final char PACKAGE_SEPARATOR = '.';

    /**
     * Тип вместе с предками
     */
    private static final class Hierarchy {
        // Простые имена всех типов иерархии
        private final Set<String> names = new LinkedHashSet<>();

        // Только типы не из исходников проекта: JDK, библиотеки, а также предки, известные лишь по имени
        private final Set<String> libraryNames = new LinkedHashSet<>();

        private final Set<String> visited = new HashSet<>();

        // false, если какого-то предка разрешить не удалось и список может быть неполным
        private boolean complete = true;
    }

    /**
     * @return тип выражения либо пусто, если он неизвестен
     */
    Optional<ResolvedType> resolve(Expression expression) {
        // Одно и то же выражение спрашивают разные правила, а разрешение - дорогая операция
        if (expression.containsData(RESOLVED_TYPE)) {
            return expression.getData(RESOLVED_TYPE);
        }

        Optional<ResolvedType> type = hasSolver(expression) ? calculate(expression) : Optional.empty();
        expression.setData(RESOLVED_TYPE, type);
        return type;
    }

    /**
     * @return тип, записанный в коде (у поля, параметра, в new или throws), либо пусто, если он неизвестен
     */
    Optional<ResolvedType> resolve(Type type) {
        if (type.containsData(RESOLVED_TYPE)) {
            return type.getData(RESOLVED_TYPE);
        }

        Optional<ResolvedType> resolved = Optional.empty();
        if (hasSolver(type)) {
            try {
                resolved = Optional.of(type.resolve());
            } catch (RuntimeException | StackOverflowError exception) {
                // Тип не найден ни в исходниках, ни в библиотеках
            }
        }
        type.setData(RESOLVED_TYPE, resolved);
        return resolved;
    }

    /**
     * Тип по имени класса, которое стоит в коде как выражение: User в User.builder().
     * У такого имени нет значения, поэтому обычным путем его тип не узнать.
     */
    Optional<ResolvedType> resolveTypeName(NameExpr name) {
        if (name.containsData(RESOLVED_TYPE_NAME)) {
            return name.getData(RESOLVED_TYPE_NAME);
        }

        Optional<ResolvedType> resolved = Optional.empty();
        if (hasSolver(name)) {
            // Решателю нужен узел-тип, стоящий в дереве: по его месту определяются импорты и пакет.
            // Временно подвешиваем такой узел к имени и сразу убираем
            ClassOrInterfaceType type = new ClassOrInterfaceType(null, name.getNameAsString());
            type.setParentNode(name);
            try {
                resolved = Optional.of(type.resolve());
            } catch (RuntimeException | StackOverflowError exception) {
                // Имя не обозначает класс либо класс не найден
            } finally {
                type.setParentNode(null);
            }
        }
        name.setData(RESOLVED_TYPE_NAME, resolved);
        return resolved;
    }

    /**
     * @return объявление типа выражения в исходниках проекта; пусто для типов из JDK и библиотек
     */
    Optional<TypeDeclaration<?>> projectType(Expression expression) {
        return resolve(expression).flatMap(Types::projectType);
    }

    Optional<TypeDeclaration<?>> projectType(ResolvedType type) {
        return declaration(type).flatMap(Types::sourceOf);
    }

    /**
     * @return простое имя типа выражения в том же виде, что и в коде: String, int, byte[], BigDecimal
     */
    Optional<String> simpleName(Expression expression) {
        return resolve(expression).flatMap(Types::simpleName);
    }

    /**
     * @return простые имена типа выражения и всех его известных предков. Предок, которого нет
     * ни в исходниках, ни в библиотеках, попадает сюда по имени из extends / implements
     */
    Set<String> hierarchy(Expression expression) {
        return resolve(expression).map(type -> hierarchy(type).names).orElse(Set.of());
    }

    /**
     * @return простые имена аннотаций на типе выражения; пусто, если тип объявлен не в исходниках проекта
     */
    Set<String> annotations(Expression expression) {
        return resolve(expression).flatMap(Types::projectAnnotations).orElse(Set.of());
    }

    /**
     * @return простые имена аннотаций на классе, если он объявлен в исходниках проекта; иначе пусто
     */
    Optional<Set<String>> projectTypeAnnotations(Type type) {
        return resolve(type).flatMap(Types::projectAnnotations);
    }

    /**
     * Проверка типа по именам: его собственному и предков. Годится и для имен классов самого проекта
     * (UserRepository, OrderClient), поэтому "нет" отвечает только для типов JDK.
     *
     * @return true - подходит; false - тип из JDK и ни одно имя не подошло; пусто - судить нужно по другим признакам
     */
    Optional<Boolean> matches(Expression expression, Predicate<String> typeName) {
        return resolve(expression).flatMap(type -> matches(type, typeName));
    }

    Optional<Boolean> matches(Type type, Predicate<String> typeName) {
        return resolve(type).flatMap(resolved -> matches(resolved, typeName));
    }

    /**
     * Является ли тип одним из библиотечных либо их наследником. Собственный класс проекта с таким же именем
     * (свой Scanner, свой Files) не подходит, а его наследник от библиотечного - подходит.
     *
     * @return true - является; false - все предки известны и среди них таких нет; пусто - тип неизвестен
     * либо известен не полностью
     */
    Optional<Boolean> isKindOf(Expression expression, Set<String> libraryTypes) {
        return resolve(expression).flatMap(type -> isKindOf(type, libraryTypes));
    }

    Optional<Boolean> isKindOf(Type type, Set<String> libraryTypes) {
        return resolve(type).flatMap(resolved -> isKindOf(resolved, libraryTypes));
    }

    /**
     * @return true, если тип выражения известен и это тип из JDK, примитив или массив
     */
    boolean isJdkType(Expression expression) {
        return resolve(expression).filter(Types::isJdkType).isPresent();
    }

    boolean isJdkType(Type type) {
        return resolve(type).filter(Types::isJdkType).isPresent();
    }

    /**
     * @return true, если вызванный метод объявлен в исходниках проверяемого проекта, а не в JDK или библиотеке
     */
    boolean isDeclaredInProject(MethodCallExpr call) {
        return declarationLine(call).isPresent();
    }

    /**
     * @return строка, на которой объявлен вызванный метод, если он найден в исходниках проекта.
     * По ней вызов сопоставляется с нужной из перегрузок
     */
    Optional<Integer> declarationLine(MethodCallExpr call) {
        return declaration(call).flatMap(Node::getBegin).map(position -> position.line);
    }

    /**
     * @return объявление вызванного метода, если оно найдено в исходниках проекта. Узел может принадлежать
     * не тому дереву, что проверяют правила: решатель разбирает файлы сам. Сопоставлять нужно по файлу и строке
     */
    Optional<MethodDeclaration> declaration(MethodCallExpr call) {
        if (!hasSolver(call)) {
            return Optional.empty();
        }
        try {
            return call.resolve().toAst()
                    .filter(node -> node instanceof MethodDeclaration)
                    .map(node -> (MethodDeclaration) node);
        } catch (RuntimeException | StackOverflowError exception) {
            return Optional.empty();
        }
    }

    /**
     * @return true, если вызов разрешен и ведет в JDK или библиотеку, а не в исходники проекта
     */
    boolean isLibraryCall(MethodCallExpr call) {
        if (!hasSolver(call)) {
            return false;
        }
        try {
            return call.resolve().toAst().isEmpty();
        } catch (RuntimeException | StackOverflowError exception) {
            return false;
        }
    }

    private boolean hasSolver(Node node) {
        return node.findCompilationUnit().filter(unit -> unit.containsData(Node.SYMBOL_RESOLVER_KEY)).isPresent();
    }

    // Решатель сообщает о неудаче исключениями разных типов, а на запутанных обобщениях может уйти в рекурсию
    private Optional<ResolvedType> calculate(Expression expression) {
        try {
            // Имя может обозначать не переменную, а класс (String в String.valueOf(...)): у класса нет значения
            // и типа выражения. resolve() находит только переменные, поля и параметры, для класса - исключение
            if (expression.isNameExpr()) {
                return Optional.of(expression.asNameExpr().resolve().getType());
            }
            if (expression.isFieldAccessExpr()) {
                return Optional.of(expression.asFieldAccessExpr().resolve().getType());
            }
            return Optional.of(expression.calculateResolvedType());
        } catch (RuntimeException | StackOverflowError exception) {
            return Lombok.resolve(expression);
        }
    }

    private Optional<Boolean> matches(ResolvedType type, Predicate<String> typeName) {
        if (hierarchy(type).names.stream().anyMatch(typeName)) {
            return Optional.of(true);
        }
        return isJdkType(type) ? Optional.of(false) : Optional.empty();
    }

    private Optional<Boolean> isKindOf(ResolvedType type, Set<String> libraryTypes) {
        if (type.isPrimitive() || type.isArray()) {
            return Optional.of(false);
        }
        if (!type.isReferenceType()) {
            return Optional.empty();
        }

        Hierarchy hierarchy = hierarchy(type);
        if (hierarchy.libraryNames.stream().anyMatch(libraryTypes::contains)) {
            return Optional.of(true);
        }
        return hierarchy.complete ? Optional.of(false) : Optional.empty();
    }

    private Optional<Set<String>> projectAnnotations(ResolvedType type) {
        return declaration(type)
                .flatMap(Types::sourceOf)
                .map(source -> annotationNames(source).collect(Collectors.toSet()));
    }

    private Optional<ResolvedReferenceTypeDeclaration> declaration(ResolvedType type) {
        return type.isReferenceType() ? type.asReferenceType().getTypeDeclaration() : Optional.empty();
    }

    // Объявление типа в исходниках проекта; у типов из JDK и библиотек его нет
    private Optional<TypeDeclaration<?>> sourceOf(ResolvedReferenceTypeDeclaration declaration) {
        try {
            return declaration.toAst()
                    .filter(node -> node instanceof TypeDeclaration<?>)
                    .map(node -> (TypeDeclaration<?>) node);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private Stream<String> annotationNames(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream().map(annotation -> annotation.getName().getIdentifier());
    }

    private Hierarchy hierarchy(ResolvedType type) {
        Hierarchy hierarchy = new Hierarchy();
        Optional<ResolvedReferenceTypeDeclaration> declaration = declaration(type);
        if (declaration.isPresent()) {
            collect(declaration.get(), hierarchy);
        } else {
            // У ссылочного типа не нашлось объявления - о предках ничего не известно
            hierarchy.complete = !type.isReferenceType();
        }
        return hierarchy;
    }

    private void collect(ResolvedReferenceTypeDeclaration type, Hierarchy hierarchy) {
        String qualifiedName = type.getQualifiedName();
        if (!hierarchy.visited.add(qualifiedName)) {
            return;
        }

        Optional<TypeDeclaration<?>> source = sourceOf(type);
        hierarchy.names.add(type.getName());
        if (source.isEmpty()) {
            hierarchy.libraryNames.add(type.getName());
            // У класса из jar предок может лежать в неподключенной библиотеке, и решатель об этом промолчит
            hierarchy.complete &= qualifiedName.startsWith(JDK_PACKAGE);
        }

        List<ResolvedReferenceType> ancestors = new ArrayList<>();
        try {
            ancestors.addAll(type.getAncestors(true));
        } catch (RuntimeException | StackOverflowError exception) {
            hierarchy.complete = false;
        }

        // Имена из extends / implements берем и текстом: предок из неподключенной библиотеки решателю неизвестен
        Set<String> resolvedNames = ancestors.stream()
                .map(ancestor -> lastName(ancestor.getQualifiedName()))
                .collect(Collectors.toSet());
        for (String parent : source.map(Types::declaredParents).orElse(List.of())) {
            hierarchy.names.add(parent);
            if (!resolvedNames.contains(parent)) {
                hierarchy.libraryNames.add(parent);
                hierarchy.complete = false;
            }
        }

        for (ResolvedReferenceType ancestor : ancestors) {
            Optional<ResolvedReferenceTypeDeclaration> parent = ancestor.getTypeDeclaration();
            if (parent.isPresent()) {
                collect(parent.get(), hierarchy);
            } else {
                hierarchy.complete = false;
            }
        }
    }

    // Имена из extends и implements так, как они записаны в коде
    private List<String> declaredParents(TypeDeclaration<?> source) {
        List<ClassOrInterfaceType> parents = new ArrayList<>();
        if (source instanceof NodeWithExtends<?> withExtends) {
            parents.addAll(withExtends.getExtendedTypes());
        }
        if (source instanceof NodeWithImplements<?> withImplements) {
            parents.addAll(withImplements.getImplementedTypes());
        }
        return parents.stream().map(ClassOrInterfaceType::getNameAsString).toList();
    }

    private Optional<String> simpleName(ResolvedType type) {
        if (type.isPrimitive()) {
            return Optional.of(type.asPrimitive().describe());
        }
        if (type.isArray()) {
            return simpleName(type.asArrayType().getComponentType()).map(component -> component + ARRAY_SUFFIX);
        }
        if (type.isReferenceType()) {
            return Optional.of(lastName(type.asReferenceType().getQualifiedName()));
        }
        // null, void, переменная типа T - имени, по которому правила узнают тип, здесь нет
        return Optional.empty();
    }

    // java.util.Map.Entry -> Entry
    private String lastName(String qualifiedName) {
        return qualifiedName.substring(qualifiedName.lastIndexOf(PACKAGE_SEPARATOR) + 1);
    }

    private boolean isJdkType(ResolvedType type) {
        return type.isPrimitive()
                || type.isArray()
                || type.isReferenceType() && type.asReferenceType().getQualifiedName().startsWith(JDK_PACKAGE);
    }
}
