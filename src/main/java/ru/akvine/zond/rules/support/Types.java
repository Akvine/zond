package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.DataKey;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithExtends;
import com.github.javaparser.ast.nodeTypes.NodeWithImplements;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import lombok.experimental.UtilityClass;
import ru.akvine.zond.loaders.FileSystemSourceLoader;

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
 * <p>
 * Методы выполняются по одному (synchronized): решатель типов и пометки на узлах дерева не рассчитаны
 * на работу из нескольких потоков, а правила при параллельном сканировании обращаются сюда одновременно.
 */
@UtilityClass
public class Types {
    private static final DataKey<Optional<ResolvedType>> RESOLVED_TYPE = new DataKey<>() {
    };
    private static final DataKey<Optional<ResolvedType>> RESOLVED_TYPE_NAME = new DataKey<>() {
    };
    private static final DataKey<CallTarget> CALL_TARGET = new DataKey<>() {
    };
    private static final DataKey<Optional<String>> WRITTEN_TYPE_NAME = new DataKey<>() {
    };
    private static final DataKey<Optional<AnnotationDeclaration>> ANNOTATION_DECLARATION = new DataKey<>() {
    };
    private static final DataKey<Optional<Set<String>>> THROWN = new DataKey<>() {
    };

    // Решатель ищет имя, перебирая предыдущие операторы блока и сравнивая их по содержимому. В блоке из тысячи
    // похожих строк (таблица проверок в тесте) одно разрешение занимает десятки миллисекунд, а весь метод - минуты.
    // В блоках длиннее этого решатель не используется: типы там определяются по объявлениям и именам
    private static final int MAX_SOLVED_BLOCK_STATEMENTS = 300;

    private static final String JDK_PACKAGE = "java.";
    private static final String JAVA_LANG = "java.lang.";
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

        // Объявления типов иерархии из исходников проекта
        private final List<TypeDeclaration<?>> sources = new ArrayList<>();

        private final Set<String> visited = new HashSet<>();

        // false, если какого-то предка разрешить не удалось и список может быть неполным
        private boolean complete = true;
    }

    /**
     * Чем закончилось разрешение вызова
     *
     * @param resolved    решатель нашел вызванный метод
     * @param declaration узел его объявления; пусто для метода из JDK или библиотеки
     */
    private record CallTarget(boolean resolved, Optional<Node> declaration) {
        private static final CallTarget UNRESOLVED = new CallTarget(false, Optional.empty());
    }

    /**
     * @return тип выражения либо пусто, если он неизвестен
     */
    public synchronized Optional<ResolvedType> resolve(Expression expression) {
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
    public synchronized Optional<ResolvedType> resolve(Type type) {
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
    public synchronized Optional<ResolvedType> resolveTypeName(NameExpr name) {
        if (name.containsData(RESOLVED_TYPE_NAME)) {
            return name.getData(RESOLVED_TYPE_NAME);
        }

        Optional<ResolvedType> resolved = name.findCompilationUnit()
                .filter(unit -> unit.containsData(FileSystemSourceLoader.TYPE_SOLVER))
                .flatMap(unit -> solveTypeName(unit, name.getNameAsString()));
        name.setData(RESOLVED_TYPE_NAME, resolved);
        return resolved;
    }

    // Ищем класс так же, как компилятор: по импортам, в своем файле и пакете, затем в java.lang.
    // Дерево при этом не меняется - его в это время могут читать другие потоки
    private Optional<ResolvedType> solveTypeName(CompilationUnit unit, String name) {
        TypeSolver solver = unit.getData(FileSystemSourceLoader.TYPE_SOLVER);
        String packageName = unit.getPackageDeclaration().map(declaration -> declaration.getNameAsString() + ".").orElse("");

        List<String> candidates = new ArrayList<>();
        for (ImportDeclaration importDeclaration : unit.getImports()) {
            if (!importDeclaration.isStatic() && !importDeclaration.isAsterisk()
                    && importDeclaration.getName().getIdentifier().equals(name)) {
                candidates.add(importDeclaration.getNameAsString());
            }
        }
        candidates.add(packageName + name);
        for (ImportDeclaration importDeclaration : unit.getImports()) {
            if (!importDeclaration.isStatic() && importDeclaration.isAsterisk()) {
                candidates.add(importDeclaration.getNameAsString() + "." + name);
            }
        }
        candidates.add(JAVA_LANG + name);

        for (String candidate : candidates) {
            try {
                SymbolReference<ResolvedReferenceTypeDeclaration> found = solver.tryToSolveType(candidate);
                if (found.isSolved()) {
                    return Optional.of(new ReferenceTypeImpl(found.getCorrespondingDeclaration()));
                }
            } catch (RuntimeException | StackOverflowError exception) {
                // Этот вариант имени не подошел - пробуем следующий
            }
        }
        return Optional.empty();
    }

    /**
     * @return объявление типа выражения в исходниках проекта; пусто для типов из JDK и библиотек
     */
    public synchronized Optional<TypeDeclaration<?>> projectType(Expression expression) {
        return resolve(expression).flatMap(Types::projectType);
    }

    public synchronized Optional<TypeDeclaration<?>> projectType(ResolvedType type) {
        return declaration(type).flatMap(Types::sourceOf);
    }

    /**
     * @return простое имя типа выражения в том же виде, что и в коде: String, int, byte[], BigDecimal
     */
    public synchronized Optional<String> simpleName(Expression expression) {
        return resolve(expression).flatMap(Types::simpleName);
    }

    /**
     * @return простые имена типа выражения и всех его известных предков. Предок, которого нет
     * ни в исходниках, ни в библиотеках, попадает сюда по имени из extends / implements
     */
    public synchronized Set<String> hierarchy(Expression expression) {
        return resolve(expression).map(type -> hierarchy(type).names).orElse(Set.of());
    }

    /**
     * @return объявления типа выражения и его предков из исходников проекта; пусто, если тип неизвестен либо
     * объявлен не в проекте
     */
    public synchronized List<TypeDeclaration<?>> projectHierarchy(Expression expression) {
        return resolve(expression).map(type -> List.copyOf(hierarchy(type).sources)).orElse(List.of());
    }

    /**
     * @return простые имена предков типа выражения не из исходников проекта: JpaRepository, CrudRepository
     * и те, что известны лишь по имени из extends / implements
     */
    public synchronized Set<String> libraryHierarchy(Expression expression) {
        return resolve(expression).map(type -> Set.copyOf(hierarchy(type).libraryNames)).orElse(Set.of());
    }

    /**
     * @return простые имена аннотаций на типе выражения; пусто, если тип объявлен не в исходниках проекта
     */
    public synchronized Set<String> annotations(Expression expression) {
        return resolve(expression).flatMap(Types::projectAnnotations).orElse(Set.of());
    }

    /**
     * @return простые имена аннотаций на классе, если он объявлен в исходниках проекта; иначе пусто
     */
    public synchronized Optional<Set<String>> projectTypeAnnotations(Type type) {
        return resolve(type).flatMap(Types::projectAnnotations);
    }

    /**
     * Проверка типа по именам: его собственному и предков. Годится и для имен классов самого проекта
     * (UserRepository, OrderClient), поэтому "нет" отвечает только для типов JDK.
     *
     * @return true - подходит; false - тип из JDK и ни одно имя не подошло; пусто - судить нужно по другим признакам
     */
    public synchronized Optional<Boolean> matches(Expression expression, Predicate<String> typeName) {
        return resolve(expression).flatMap(type -> matches(type, typeName));
    }

    public synchronized Optional<Boolean> matches(Type type, Predicate<String> typeName) {
        return resolve(type).flatMap(resolved -> matches(resolved, typeName));
    }

    /**
     * Является ли тип одним из библиотечных либо их наследником. Собственный класс проекта с таким же именем
     * (свой Scanner, свой Files) не подходит, а его наследник от библиотечного - подходит.
     *
     * @return true - является; false - все предки известны и среди них таких нет; пусто - тип неизвестен
     * либо известен не полностью
     */
    public synchronized Optional<Boolean> isKindOf(Expression expression, Set<String> libraryTypes) {
        return resolve(expression).flatMap(type -> isKindOf(type, libraryTypes));
    }

    public synchronized Optional<Boolean> isKindOf(Type type, Set<String> libraryTypes) {
        return resolve(type).flatMap(resolved -> isKindOf(resolved, libraryTypes));
    }

    /**
     * @return true, если тип выражения известен и это тип из JDK, примитив или массив
     */
    public synchronized boolean isJdkType(Expression expression) {
        return resolve(expression).filter(Types::isJdkType).isPresent();
    }

    public synchronized boolean isJdkType(Type type) {
        return resolve(type).filter(Types::isJdkType).isPresent();
    }

    /**
     * @return true, если вызванный метод объявлен в исходниках проверяемого проекта, а не в JDK или библиотеке
     */
    public synchronized boolean isDeclaredInProject(MethodCallExpr call) {
        return declarationLine(call).isPresent();
    }

    /**
     * @return строка, на которой объявлен вызванный метод, если он найден в исходниках проекта.
     * По ней вызов сопоставляется с нужной из перегрузок
     */
    public synchronized Optional<Integer> declarationLine(MethodCallExpr call) {
        return declaration(call).flatMap(Node::getBegin).map(position -> position.line);
    }

    /**
     * @return объявление вызванного метода, если оно найдено в исходниках проекта. Узел может принадлежать
     * не тому дереву, что проверяют правила: файл, которого загрузчик не читал, решатель разбирает сам.
     * Сопоставлять нужно по файлу и строке
     */
    public synchronized Optional<MethodDeclaration> declaration(MethodCallExpr call) {
        return target(call).declaration()
                .filter(node -> node instanceof MethodDeclaration)
                .map(node -> (MethodDeclaration) node);
    }

    /**
     * @return простые имена исключений из throws вызванного метода - своего либо библиотечного; пусто, если
     * вызов не удалось разрешить. Нужен редко, поэтому считается отдельно от цели вызова и только по запросу
     */
    public synchronized Optional<Set<String>> thrownBy(MethodCallExpr call) {
        if (call.containsData(THROWN)) {
            return call.getData(THROWN);
        }
        Optional<Set<String>> thrown = Optional.empty();
        if (hasSolver(call)) {
            try {
                Set<String> names = new HashSet<>();
                for (ResolvedType type : call.resolve().getSpecifiedExceptions()) {
                    names.add(type.isReferenceType()
                            ? lastName(type.asReferenceType().getQualifiedName())
                            : type.describe());
                }
                thrown = Optional.of(names);
            } catch (RuntimeException | StackOverflowError exception) {
                // Метод не найден либо его класса нет в classpath
            }
        }
        call.setData(THROWN, thrown);
        return thrown;
    }

    /**
     * @return true, если вызов разрешен и ведет в JDK или библиотеку, а не в исходники проекта
     */
    public synchronized boolean isLibraryCall(MethodCallExpr call) {
        CallTarget target = target(call);
        return target.resolved() && target.declaration().isEmpty();
    }

    // Один и тот же вызов разрешают граф вызовов и несколько правил, а это самая дорогая операция решателя
    private CallTarget target(MethodCallExpr call) {
        if (call.containsData(CALL_TARGET)) {
            return call.getData(CALL_TARGET);
        }

        CallTarget target = CallTarget.UNRESOLVED;
        if (hasSolver(call)) {
            try {
                target = new CallTarget(true, call.resolve().toAst());
            } catch (RuntimeException | StackOverflowError exception) {
                // Метод не найден либо решатель не справился с перегрузками и обобщениями
            }
        }
        call.setData(CALL_TARGET, target);
        return target;
    }

    /**
     * @return объявление аннотации, если она объявлена в исходниках проекта: по нему видно, чем она помечена
     * сама - {@code @AfterStartUp} несет {@code @EventListener(ApplicationReadyEvent.class)}
     */
    public synchronized Optional<AnnotationDeclaration> projectAnnotation(AnnotationExpr annotation) {
        if (annotation.containsData(ANNOTATION_DECLARATION)) {
            return annotation.getData(ANNOTATION_DECLARATION);
        }
        Optional<AnnotationDeclaration> declaration = Optional.empty();
        if (hasSolver(annotation)) {
            try {
                declaration = annotation.resolve().toAst()
                        .filter(node -> node instanceof AnnotationDeclaration)
                        .map(node -> (AnnotationDeclaration) node);
            } catch (RuntimeException | StackOverflowError exception) {
                // Аннотация из библиотеки, которой нет в classpath: ее объявление неизвестно
            }
        }
        annotation.setData(ANNOTATION_DECLARATION, declaration);
        return declaration;
    }

    private boolean hasSolver(Node node) {
        return !isInHugeBlock(node)
                && node.findCompilationUnit().filter(unit -> unit.containsData(Node.SYMBOL_RESOLVER_KEY)).isPresent();
    }

    private boolean isInHugeBlock(Node node) {
        Node current = node;
        while (current != null) {
            if (current instanceof BlockStmt block && block.getStatements().size() > MAX_SOLVED_BLOCK_STATEMENTS) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    // Решатель сообщает о неудаче исключениями разных типов, а на запутанных обобщениях может уйти в рекурсию
    private Optional<ResolvedType> calculate(Expression expression) {
        try {
            // Имя может обозначать не переменную, а класс (String в String.valueOf(...)): у класса нет значения
            // и типа выражения. resolve() находит только переменные, поля и параметры, для класса - исключение
            if (expression.isNameExpr()) {
                return Optional.of(valueType(expression.asNameExpr().resolve().getType()));
            }
            if (expression.isFieldAccessExpr()) {
                return Optional.of(valueType(expression.asFieldAccessExpr().resolve().getType()));
            }
            return Optional.of(valueType(expression.calculateResolvedType()));
        } catch (RuntimeException | StackOverflowError exception) {
            return Lombok.resolve(expression).or(() -> elementType(expression));
        }
    }

    // Параметр лямбды и переменная for-each без типа: решатель не справился с цепочкой, но тип элемента записан
    // в объявлении коллекции, которую перебирают
    private Optional<ResolvedType> elementType(Expression expression) {
        if (!expression.isNameExpr()) {
            return Optional.empty();
        }
        Optional<Node> declaration = LocalTypes.findDeclaration(expression);
        Optional<Type> written = Optional.empty();
        if (declaration.isPresent() && declaration.get() instanceof Parameter parameter) {
            written = ElementTypes.of(parameter);
        } else if (declaration.isPresent() && declaration.get() instanceof VariableDeclarator variable
                && variable.getType().isVarType()) {
            written = ElementTypes.of(variable);
        }
        return written.flatMap(Types::resolve);
    }

    /**
     * Тип значения вместо записи с подстановочным знаком. Параметр лямбды решатель описывает так, как он объявлен
     * у функционального интерфейса: у forEach(Consumer<? super T>) это "? super Order", хотя в теле лямбды значение
     * имеет тип Order. Так же значение из List<? extends Order> - это Order. А из List<? super Order> достается
     * Object: такой тип остается как есть
     */
    private ResolvedType valueType(ResolvedType type) {
        if (type.isConstraint()) {
            return type.asConstraintType().getBound();
        }
        return type.isWildcard() && type.asWildcard().isExtends() ? type.asWildcard().getBoundedType() : type;
    }

    /**
     * Имя типа так, как оно записано в коде проекта, когда сам тип разрешить не удалось: его библиотеки нет в
     * classpath. {@code HttpClient client()} в классе проекта: у client().execute(...) получатель - HttpClient,
     * хотя что это за класс, неизвестно. То же с полем класса-предка и переменной из instanceof
     *
     * @return простое имя типа из объявления метода, поля или переменной, найденного в исходниках проекта
     */
    public synchronized Optional<String> writtenTypeName(Expression expression) {
        if (expression.containsData(WRITTEN_TYPE_NAME)) {
            return expression.getData(WRITTEN_TYPE_NAME);
        }
        Optional<String> name = hasSolver(expression) ? findWrittenTypeName(expression) : Optional.empty();
        expression.setData(WRITTEN_TYPE_NAME, name);
        return name;
    }

    private Optional<String> findWrittenTypeName(Expression expression) {
        try {
            if (expression.isMethodCallExpr()) {
                return target(expression.asMethodCallExpr()).declaration()
                        .filter(node -> node instanceof MethodDeclaration)
                        .map(node -> (MethodDeclaration) node)
                        .filter(method -> !isTypeParameter(method.getType(), method))
                        .flatMap(method -> writtenName(method.getType()));
            }
            Optional<Node> declaration = Optional.empty();
            if (expression.isNameExpr()) {
                declaration = expression.asNameExpr().resolve().toAst();
            } else if (expression.isFieldAccessExpr()) {
                declaration = expression.asFieldAccessExpr().resolve().toAst();
            }
            return declaration.flatMap(Types::writtenTypeOf).flatMap(Types::writtenName);
        } catch (RuntimeException | StackOverflowError exception) {
            return Optional.empty();
        }
    }

    // Решатель отдает поле целиком: по private Logger first, second; имя одно на все переменные
    private Optional<Type> writtenTypeOf(Node declaration) {
        if (declaration instanceof FieldDeclaration field) {
            return Optional.of(field.getCommonType());
        }
        if (declaration instanceof VariableDeclarator variable) {
            return Optional.of(variable.getType());
        }
        if (declaration instanceof Parameter parameter) {
            return Optional.of(parameter.getType());
        }
        return declaration instanceof TypePatternExpr pattern ? Optional.of(pattern.getType()) : Optional.empty();
    }

    // java.util.Map<String, String> -> Map; у var, void и неизвестного типа параметра лямбды имени нет
    private Optional<String> writtenName(Type type) {
        return type.isClassOrInterfaceType()
                ? Optional.of(type.asClassOrInterfaceType().getNameAsString())
                : Optional.empty();
    }

    // <T> T find(...) и class Box<T> { T get() }: T - не имя класса
    private boolean isTypeParameter(Type type, MethodDeclaration method) {
        if (!type.isClassOrInterfaceType()) {
            return false;
        }
        String name = type.asClassOrInterfaceType().getNameAsString();
        if (method.getTypeParameters().stream().anyMatch(parameter -> parameter.getNameAsString().equals(name))) {
            return true;
        }
        Node current = method.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof ClassOrInterfaceDeclaration owner && owner.getTypeParameters().stream()
                    .anyMatch(parameter -> parameter.getNameAsString().equals(name))) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
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
        source.ifPresent(hierarchy.sources::add);
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
