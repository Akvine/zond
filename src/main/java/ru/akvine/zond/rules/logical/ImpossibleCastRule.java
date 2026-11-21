package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.DataObjects;
import ru.akvine.zond.rules.support.Guards;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.SpringBeans;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Приведение к классу, которым значение быть не может: переменной присваивают только объекты класса A, а приводят
 * ее к классу B, который с A не связан наследованием. Компилятор такое пропускает, когда сама переменная объявлена
 * интерфейсом или общим предком, но у класса один предок: объект A и его наследники никогда не окажутся B.
 * <p>
 * Судим только о переменной, все значения которой видны в коде: локальной либо закрытом поле, которое не заполняют
 * ни контейнер, ни Lombok, ни библиотека, читающая объект из JSON или базы
 */
@Component
public class ImpossibleCastRule extends AbstractRule implements ProjectRule {
    private static final Set<String> INJECTION_ANNOTATIONS =
            Set.of("Autowired", "Inject", "Resource", "Value", "PersistenceContext", "Mock", "MockBean", "Spy");
    // Lombok пишет в поле кодом, которого в исходниках нет
    private static final Set<String> FIELD_WRITERS = Set.of("Setter", "With", "Wither");
    private static final Set<String> CLASS_WRITERS =
            Set.of("Setter", "Data", "Value", "With", "Wither", "AllArgsConstructor", "Builder", "SuperBuilder");
    private static final String REQUIRED_ARGS_CONSTRUCTOR = "RequiredArgsConstructor";
    private static final String NON_NULL = "NonNull";
    // bot.getClass() == X.class, X.class.isInstance(bot): класс значения проверили сами
    private static final String GET_CLASS = "getClass";
    private static final Set<String> CLASS_CHECKS = Set.of("isInstance", "isAssignableFrom", "isInstanceOf");
    // Дальше родителей не идем: такой глубины у наследования не бывает, а цикл в сломанном коде возможен
    private static final int MAX_PARENTS = 20;
    private static final int MAX_ALIASES = 3;

    @Override
    public String code() {
        return RuleCodes.IMPOSSIBLE_CAST_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет приведение переменной к классу, не связанному с классом ее значений";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (CastExpr cast : sourceFile.findAll(CastExpr.class)) {
                if (TestClasses.isInside(cast)) {
                    continue;
                }
                Optional<ClassOrInterfaceDeclaration> target = classOf(cast.getType(), classes);
                Expression value = Nodes.unwrap(cast.getExpression());
                Optional<VariableDeclarator> variable = target.flatMap(found -> variableOf(value));
                if (variable.isEmpty() || isFilledElsewhere(variable.get()) || isTypeChecked(cast, value)) {
                    continue;
                }
                List<ClassOrInterfaceDeclaration> assigned = assignedClasses(variable.get(), classes);
                boolean unrelated = !assigned.isEmpty() && assigned.stream()
                        .allMatch(source -> isUnrelated(source, target.get(), classes));
                if (unrelated) {
                    Set<String> names = assigned.stream()
                            .map(ClassOrInterfaceDeclaration::getNameAsString)
                            .collect(Collectors.toCollection(TreeSet::new));
                    Violation violation = violation(sourceFile, cast,
                            "'" + cast + "': переменной '" + value + "' присваивают только "
                                    + String.join(", ", names) + ", а " + target.get().getNameAsString()
                                    + " с этим классом наследованием не связан - приведение всегда заканчивается"
                                    + " ClassCastException; приводите к общему интерфейсу либо храните значение"
                                    + " под тем типом, который нужен");
                    violations.add(isSureAboutValues(variable.get())
                            ? violation
                            : violation.withConfidence(Confidence.SUSPICION));
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
        return ErrorType.LOGICAL;
    }

    // Класс проекта, названный в коде: с учетом импортов, чтобы класс библиотеки не приняли за одноименный свой
    private Optional<ClassOrInterfaceDeclaration> classOf(Type type, ProjectClasses classes) {
        if (!type.isClassOrInterfaceType() || isTypeParameter(type.asClassOrInterfaceType())) {
            return Optional.empty();
        }
        return classes.resolve(type.asClassOrInterfaceType()).filter(found -> !found.isInterface());
    }

    // <T extends Bot> ... (T) bot: T - не класс, даже если класс с таким именем в проекте есть
    private boolean isTypeParameter(ClassOrInterfaceType type) {
        String name = type.getNameAsString();
        for (Node current = type; current != null; current = current.getParentNode().orElse(null)) {
            if (current instanceof NodeWithTypeParameters<?> generic && generic.getTypeParameters().stream()
                    .anyMatch(parameter -> parameter.getNameAsString().equals(name))) {
                return true;
            }
        }
        return false;
    }

    // x либо this.x, объявленные переменной: о параметре метода ничего не известно - его значение приходит извне
    private Optional<VariableDeclarator> variableOf(Expression value) {
        return LocalTypes.findDeclaration(value)
                .filter(VariableDeclarator.class::isInstance)
                .map(VariableDeclarator.class::cast);
    }

    private boolean isTypeChecked(CastExpr cast, Expression value) {
        String variable = value.toString();
        return Guards.isGuarded(cast, check -> isTypeCheck(check, variable));
    }

    private boolean isTypeCheck(Expression check, String variable) {
        if (check.isInstanceOfExpr()) {
            return check.asInstanceOfExpr().getExpression().toString().equals(variable);
        }
        if (!check.isMethodCallExpr()) {
            return false;
        }
        MethodCallExpr call = check.asMethodCallExpr();
        if (GET_CLASS.equals(call.getNameAsString())) {
            return call.getScope().filter(scope -> scope.toString().equals(variable)).isPresent();
        }
        return CLASS_CHECKS.contains(call.getNameAsString()) && call.getArguments().stream()
                .anyMatch(argument -> argument.toString().equals(variable));
    }

    /**
     * @return true, если значение переменной попадает в нее не только присваиваниями, которые видны в коде
     */
    private boolean isFilledElsewhere(VariableDeclarator variable) {
        Optional<FieldDeclaration> field = fieldOf(variable);
        if (field.isEmpty()) {
            // for (Bot bot : bots): значение дает сама коллекция
            return variable.getParentNode().flatMap(Node::getParentNode)
                    .filter(ForEachStmt.class::isInstance)
                    .isPresent();
        }
        Optional<ClassOrInterfaceDeclaration> owner = field.get().getParentNode()
                .filter(ClassOrInterfaceDeclaration.class::isInstance)
                .map(ClassOrInterfaceDeclaration.class::cast);
        if (owner.isEmpty() || !field.get().isPrivate() || DataObjects.isDataObject(owner.get())) {
            return true;
        }
        boolean byConstructor = Annotations.has(owner.get(), REQUIRED_ARGS_CONSTRUCTOR)
                && (field.get().isFinal() || Annotations.has(field.get(), NON_NULL));
        return byConstructor
                || Annotations.hasAny(field.get(), INJECTION_ANNOTATIONS)
                || Annotations.hasAny(field.get(), FIELD_WRITERS)
                || Annotations.hasAny(owner.get(), CLASS_WRITERS);
    }

    // В поле бина пишет только его же код. В поле обычного класса значение может положить и библиотека,
    // которая собирает объект из JSON или из строки базы в обход конструкторов
    private boolean isSureAboutValues(VariableDeclarator variable) {
        return fieldOf(variable)
                .flatMap(Node::getParentNode)
                .filter(owner -> !(owner instanceof ClassOrInterfaceDeclaration type && SpringBeans.isBean(type)))
                .isEmpty();
    }

    private Optional<FieldDeclaration> fieldOf(VariableDeclarator variable) {
        return variable.getParentNode()
                .filter(FieldDeclaration.class::isInstance)
                .map(FieldDeclaration.class::cast);
    }

    /**
     * @return классы проекта, объекты которых присваивают переменной; пусто, если хоть одно значение неизвестно
     */
    private List<ClassOrInterfaceDeclaration> assignedClasses(VariableDeclarator variable, ProjectClasses classes) {
        boolean field = fieldOf(variable).isPresent();
        // В закрытое поле пишут где угодно в файле - и во вложенных классах тоже; в локальную переменную - в ее методе
        Optional<Node> scope = field
                ? variable.findCompilationUnit().map(unit -> unit)
                : Nodes.enclosingCallable(variable);
        if (scope.isEmpty()) {
            return List.of();
        }
        List<Expression> values = new ArrayList<>();
        variable.getInitializer().ifPresent(values::add);
        for (AssignExpr assignment : scope.get().findAll(AssignExpr.class)) {
            Expression target = Nodes.unwrap(assignment.getTarget());
            if (LocalTypes.findDeclaration(target).filter(found -> found == variable).isPresent()) {
                values.add(assignment.getValue());
            } else if (field && isFieldOfAnotherObject(target, variable.getNameAsString())) {
                // other.bot = ...: возможно, это то же поле другого объекта - всех значений мы не знаем
                return List.of();
            }
        }
        List<ClassOrInterfaceDeclaration> assigned = new ArrayList<>();
        for (Expression value : values) {
            Optional<ClassOrInterfaceDeclaration> type = classOf(Nodes.unwrap(value), classes, 0);
            if (type.isEmpty()) {
                return List.of();
            }
            assigned.add(type.get());
        }
        return assigned;
    }

    private boolean isFieldOfAnotherObject(Expression target, String name) {
        return target.isFieldAccessExpr()
                && !target.asFieldAccessExpr().getScope().isThisExpr()
                && target.asFieldAccessExpr().getNameAsString().equals(name);
    }

    // new TelegramDevBot(...) либо параметр или переменная, объявленные классом: в них лежит он сам или наследник
    private Optional<ClassOrInterfaceDeclaration> classOf(Expression value, ProjectClasses classes, int depth) {
        if (value.isObjectCreationExpr()) {
            return classOf(value.asObjectCreationExpr().getType(), classes);
        }
        if (!value.isNameExpr() || depth > MAX_ALIASES) {
            return Optional.empty();
        }
        Optional<Node> declaration = LocalTypes.findDeclaration(value);
        if (declaration.filter(Parameter.class::isInstance).isPresent()) {
            return classOf(((Parameter) declaration.get()).getType(), classes);
        }
        if (declaration.filter(VariableDeclarator.class::isInstance).isEmpty()) {
            return Optional.empty();
        }
        VariableDeclarator variable = (VariableDeclarator) declaration.get();
        // var bot = new TelegramDevBot(): класс виден по значению
        if (!variable.getType().isVarType()) {
            return classOf(variable.getType(), classes);
        }
        return variable.getInitializer()
                .flatMap(initializer -> classOf(Nodes.unwrap(initializer), classes, depth + 1));
    }

    // Два класса проекта, ни один из которых не предок другого
    private boolean isUnrelated(ClassOrInterfaceDeclaration first, ClassOrInterfaceDeclaration second,
                                ProjectClasses classes) {
        return !isSame(first, second) && !inherits(first, second, classes) && !inherits(second, first, classes);
    }

    private boolean isSame(ClassOrInterfaceDeclaration first, ClassOrInterfaceDeclaration second) {
        return first == second || first.getFullyQualifiedName().isPresent()
                && first.getFullyQualifiedName().equals(second.getFullyQualifiedName());
    }

    private boolean inherits(ClassOrInterfaceDeclaration child, ClassOrInterfaceDeclaration ancestor,
                             ProjectClasses classes) {
        ClassOrInterfaceDeclaration current = child;
        for (int depth = 0; depth < MAX_PARENTS; depth++) {
            if (current.getExtendedTypes().isEmpty()) {
                return false;
            }
            Optional<ClassOrInterfaceDeclaration> parent = classes.parent(current);
            if (parent.isEmpty()) {
                // Предок из библиотеки классом проекта не окажется. Но если такое имя в проекте есть, а понять,
                // о ком речь, не удалось, родство не исключено
                return classes.isDeclared(current.getExtendedTypes().get(0).getNameAsString());
            }
            if (isSame(parent.get(), ancestor)) {
                return true;
            }
            current = parent.get();
        }
        return true;
    }
}
