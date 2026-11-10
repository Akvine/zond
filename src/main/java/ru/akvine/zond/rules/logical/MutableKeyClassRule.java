package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Lombok;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Свой класс в роли ключа HashMap или элемента HashSet должен быть неизменяемым. Место записи в таблице
 * выбирается по hashCode в момент вставки: если после нее поле, от которого зависит hashCode, изменить,
 * запись останется в прежней корзине - get вернет null, contains - false, а remove ее не удалит.
 */
@Component
public class MutableKeyClassRule extends AbstractRule implements ProjectRule {
    private static final String HASH_CODE = "hashCode";
    private static final Set<String> MAP_TYPES = Set.of(
            "Map", "HashMap", "LinkedHashMap", "ConcurrentHashMap", "ConcurrentMap", "Hashtable", "WeakHashMap");
    private static final Set<String> SET_TYPES = Set.of("Set", "HashSet", "LinkedHashSet");

    // Lombok строит hashCode по полям класса
    private static final Set<String> LOMBOK_HASH_CODE = Set.of("Data", "EqualsAndHashCode");
    // Сущности сравнивают по идентификатору, и про их hashCode есть отдельное правило
    private static final Set<String> STORED = Set.of("Entity", "MappedSuperclass", "Embeddable", "Document");
    private static final String VALUE = "Value";
    private static final String EXCLUDE = "EqualsAndHashCode.Exclude";
    // count++, --count: поле меняется без присваивания
    private static final Set<UnaryExpr.Operator> CHANGING_OPERATORS = Set.of(
            UnaryExpr.Operator.PREFIX_INCREMENT, UnaryExpr.Operator.PREFIX_DECREMENT,
            UnaryExpr.Operator.POSTFIX_INCREMENT, UnaryExpr.Operator.POSTFIX_DECREMENT);

    @Override
    public String code() {
        return RuleCodes.MUTABLE_KEY_CLASS_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет свои изменяемые классы, которые служат ключом HashMap или элементом"
                + " HashSet: после изменения ключа запись в коллекции перестает находиться";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        // Класс-ключ -> первое место, где он использован ключом
        Map<ClassOrInterfaceDeclaration, String> usages = new LinkedHashMap<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceType type : sourceFile.unit().findAll(ClassOrInterfaceType.class)) {
                if (TestClasses.isInside(type)) {
                    continue;
                }
                keyType(type).flatMap(classes::find).ifPresent(key -> usages.putIfAbsent(key,
                        Nodes.text(type) + " в " + sourceFile.path().getFileName() + ":"
                                + type.getBegin().map(position -> position.line).orElse(0)));
            }
        }

        List<Violation> violations = new ArrayList<>();
        usages.forEach((key, usage) -> describeMutability(key).ifPresent(reason -> violations.add(violation(
                classes.fileOf(key), key.getName(),
                "Класс '" + key.getNameAsString() + "' служит ключом хеш-таблицы (" + usage + "), но его можно"
                        + " изменить после вставки: " + reason + ". Место записи выбирается по hashCode в момент"
                        + " вставки, и измененный ключ остается в прежней корзине - get вернет null, contains -"
                        + " false, а remove запись не удалит; сделайте класс неизменяемым: final-поля без сеттеров,"
                        + " record либо @Value"))));
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    // Map<Key, V>, Set<Key>: имя типа ключа; у вложенных параметров (Map<String, List<Key>>) ключ - String
    private Optional<String> keyType(ClassOrInterfaceType type) {
        String container = type.getNameAsString();
        if (!MAP_TYPES.contains(container) && !SET_TYPES.contains(container)) {
            return Optional.empty();
        }
        return type.getTypeArguments()
                .filter(arguments -> !arguments.isEmpty() && arguments.get(0).isClassOrInterfaceType())
                .map(arguments -> LocalTypes.typeName(arguments.get(0)));
    }

    /**
     * @return почему ключ изменяем; пусто, если hashCode от полей не зависит либо менять их нечем
     */
    private Optional<String> describeMutability(ClassOrInterfaceDeclaration key) {
        if (key.isInterface() || Annotations.hasAny(key, STORED) || Annotations.has(key, VALUE)) {
            return Optional.empty();
        }
        Optional<MethodDeclaration> hashCode = key.getMethodsByName(HASH_CODE).stream()
                .filter(method -> method.getParameters().isEmpty())
                .findFirst();
        boolean lombok = Annotations.hasAny(key, LOMBOK_HASH_CODE);
        // Без своего hashCode объект ищется по ссылке, и изменение полей ему не мешает
        if (hashCode.isEmpty() && !lombok) {
            return Optional.empty();
        }
        for (FieldDeclaration field : key.getFields()) {
            if (field.isStatic() || field.isTransient() || field.isFinal()) {
                continue;
            }
            for (VariableDeclarator variable : field.getVariables()) {
                String name = variable.getNameAsString();
                // Lombok берет в hashCode все поля, кроме помеченных @EqualsAndHashCode.Exclude
                boolean hashed = hashCode.map(method -> usesField(method, name)).orElseGet(() -> !isExcluded(field));
                Optional<String> change = hashed ? changeOf(key, field, name) : Optional.empty();
                if (change.isPresent()) {
                    return Optional.of("поле '" + name + "' входит в hashCode, не объявлено final и меняется " + change.get());
                }
            }
        }
        return Optional.empty();
    }

    private boolean isExcluded(FieldDeclaration field) {
        return field.getAnnotations().stream().anyMatch(annotation -> annotation.toString().contains(EXCLUDE));
    }

    // hashCode обращается к полю прямо либо через геттер
    private boolean usesField(MethodDeclaration hashCode, String field) {
        String getter = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        return hashCode.findAll(Expression.class).stream().anyMatch(expression ->
                expression.isNameExpr() && expression.asNameExpr().getNameAsString().equals(field)
                        || expression.isFieldAccessExpr() && expression.asFieldAccessExpr().getNameAsString().equals(field)
                        || expression.isMethodCallExpr() && expression.asMethodCallExpr().getNameAsString().equals(getter));
    }

    /**
     * @return чем поле меняют после создания объекта: сеттером Lombok, своим сеттером либо в другом методе
     */
    private Optional<String> changeOf(ClassOrInterfaceDeclaration key, FieldDeclaration field, String name) {
        if (Lombok.hasSetter(key, field)) {
            return Optional.of("сеттером Lombok");
        }
        if (!field.isPrivate()) {
            return Optional.of("напрямую: оно не закрыто");
        }
        for (MethodDeclaration method : key.getMethods()) {
            boolean assigns = method.findAll(AssignExpr.class).stream()
                    .anyMatch(assignment -> isField(assignment.getTarget(), name))
                    || method.findAll(UnaryExpr.class).stream()
                    .filter(unary -> CHANGING_OPERATORS.contains(unary.getOperator()))
                    .anyMatch(unary -> isField(unary.getExpression(), name));
            if (assigns && !method.isStatic()) {
                return Optional.of("в методе '" + method.getNameAsString() + "'");
            }
        }
        return Optional.empty();
    }

    // this.name либо name, если в методе нет одноименной локальной переменной или параметра
    private boolean isField(Expression target, String name) {
        Expression value = Nodes.unwrap(target);
        if (value.isFieldAccessExpr()) {
            return value.asFieldAccessExpr().getScope().isThisExpr() && value.asFieldAccessExpr().getNameAsString().equals(name);
        }
        if (!value.isNameExpr() || !value.asNameExpr().getNameAsString().equals(name)) {
            return false;
        }
        Optional<Node> declaration = LocalTypes.findDeclaration(value);
        return declaration.isEmpty() || declaration.get().findAncestor(MethodDeclaration.class).isEmpty()
                && declaration.get().findAncestor(ConstructorDeclaration.class).isEmpty();
    }
}
