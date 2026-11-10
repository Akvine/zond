package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.Statement;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.BeanScopes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Lombok;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class InternalCollectionExposureRule extends AbstractRule {
    private static final Set<String> MUTABLE_TYPES = Set.of(
            "List", "Set", "Map", "Collection", "ArrayList", "LinkedList", "HashSet", "HashMap", "TreeMap", "Date");

    // Сущностям и классам с данными (DTO) отдавать коллекцию как есть положено по их назначению
    private static final Set<String> DATA_CLASS_ANNOTATIONS =
            Set.of("Entity", "Embeddable", "MappedSuperclass", "Data", "Value", "Getter", "ConfigurationProperties");

    // У бина-одиночки геттеры Lombok данными его не делают: под запретом остаются только хранимые классы и настройки
    private static final Set<String> STORED_CLASS_ANNOTATIONS =
            Set.of("Entity", "Embeddable", "MappedSuperclass", "ConfigurationProperties");
    private static final Set<String> LOMBOK_GETTERS = Set.of("Getter", "Data", "Value");
    private static final String GETTER = "Getter";
    // @Getter(AccessLevel.NONE) и @Getter(AccessLevel.PRIVATE) наружу поле не отдают
    private static final Set<String> HIDDEN_ACCESS = Set.of("NONE", "PRIVATE");
    private static final String SHARED = " Бин один на все приложение: изменение увидят все запросы и потоки сразу";

    // Коллекция уже неизменяема
    private static final Set<String> IMMUTABLE_FACTORIES = Set.of(
            "of", "copyOf", "emptyList", "emptySet", "emptyMap", "unmodifiableList", "unmodifiableSet",
            "unmodifiableMap", "unmodifiableCollection", "toList");

    @Override
    public String code() {
        return RuleCodes.INTERNAL_COLLECTION_EXPOSURE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет геттеры, которые отдают наружу внутреннюю изменяемую коллекцию или массив,"
                + " в том числе геттеры Lombok у бинов-одиночек";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            boolean singleton = BeanScopes.isSingletonBean(type);
            if (type.isInterface() || Annotations.hasAny(type, singleton ? STORED_CLASS_ANNOTATIONS : DATA_CLASS_ANNOTATIONS)) {
                continue;
            }
            if (singleton) {
                reportLombokGetters(sourceFile, type, violations);
            }
            for (MethodDeclaration method : type.getMethods()) {
                if (method.isPrivate() || method.isStatic() || !method.getParameters().isEmpty()) {
                    continue;
                }
                findReturnedField(method)
                        .flatMap(field -> LocalTypes.findField(method, field))
                        .filter(this::isMutable)
                        // У поля есть сеттер: класс - носитель данных, и его состояние и так меняют снаружи
                        .filter(field -> !hasSetter(type, field.getNameAsString()))
                        .ifPresent(field -> violations.add(violation(sourceFile, method,
                                "Метод '" + method.getNameAsString() + "' отдает внутреннее поле '"
                                        + field.getNameAsString() + "' как есть: вызывающий код сможет менять"
                                        + " состояние объекта в обход его методов; верните копию или"
                                        + " неизменяемое представление: List.copyOf(...),"
                                        + " Collections.unmodifiableList(...)." + (singleton ? SHARED : ""))));
            }
        }
        violations.sort(Comparator.comparingInt(Violation::line));
        return violations;
    }

    // @Getter над полем или над классом бина: метода в коде нет, но коллекцию он отдает точно так же
    private void reportLombokGetters(SourceFile sourceFile, ClassOrInterfaceDeclaration type, List<Violation> violations) {
        boolean onClass = Annotations.hasAny(type, LOMBOK_GETTERS);
        for (FieldDeclaration field : type.getFields()) {
            if (field.isStatic() || isHidden(field) || !(onClass || Annotations.has(field, GETTER))) {
                continue;
            }
            for (VariableDeclarator variable : field.getVariables()) {
                String name = variable.getNameAsString();
                String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
                // Геттер написан руками - Lombok свой не создаст, а написанный проверен выше
                if (!isMutable(variable) || hasSetter(type, name) || Lombok.hasSetter(type, field)
                        || !type.getMethodsByName(getter).isEmpty()) {
                    continue;
                }
                violations.add(violation(sourceFile, variable,
                        "Геттер Lombok отдает внутреннее поле '" + name + "' бина '" + type.getNameAsString()
                                + "' как есть: вызывающий код сможет менять состояние бина в обход его методов;"
                                + " напишите геттер сами и верните копию или неизменяемое представление:"
                                + " List.copyOf(...), Collections.unmodifiableList(...)." + SHARED));
            }
        }
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    private boolean isHidden(FieldDeclaration field) {
        String getter = Annotations.find(field, GETTER).map(Object::toString).orElse("");
        return HIDDEN_ACCESS.stream().anyMatch(getter::contains);
    }

    // Тело метода - единственный оператор return field; либо return this.field;
    private boolean hasSetter(ClassOrInterfaceDeclaration type, String field) {
        String setter = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        return type.getMethodsByName(setter).stream()
                .anyMatch(method -> !method.isPrivate() && method.getParameters().size() == 1);
    }

    private Optional<String> findReturnedField(MethodDeclaration method) {
        List<Statement> statements = method.getBody().map(body -> (List<Statement>) body.getStatements()).orElse(List.of());
        if (statements.size() != 1 || !statements.get(0).isReturnStmt()) {
            return Optional.empty();
        }

        Optional<Expression> returned = statements.get(0).asReturnStmt().getExpression().map(Nodes::unwrap);
        if (returned.filter(Expression::isNameExpr).isPresent()) {
            return Optional.of(returned.get().asNameExpr().getNameAsString());
        }
        return returned
                .filter(value -> value.isFieldAccessExpr() && value.asFieldAccessExpr().getScope().isThisExpr())
                .map(value -> value.asFieldAccessExpr().getNameAsString());
    }

    private boolean isMutable(VariableDeclarator field) {
        boolean mutableType = MUTABLE_TYPES.contains(LocalTypes.typeName(field.getType())) || field.getType().isArrayType();
        boolean immutableValue = field.getInitializer().filter(this::isImmutable).isPresent()
                || isAssignedOnlyImmutable(field);
        return mutableType && !immutableValue;
    }

    // this.columns = List.copyOf(columns) в конструкторе: снаружи отдают копию, которую нельзя изменить
    private boolean isAssignedOnlyImmutable(VariableDeclarator field) {
        String name = field.getNameAsString();
        List<Expression> values = field.findAncestor(ClassOrInterfaceDeclaration.class).stream()
                .flatMap(type -> type.findAll(AssignExpr.class).stream())
                .filter(assignment -> isField(assignment.getTarget(), name))
                .map(AssignExpr::getValue)
                .toList();
        return !values.isEmpty() && field.getInitializer().isEmpty() && values.stream().allMatch(this::isImmutable);
    }

    private boolean isField(Expression target, String name) {
        return target.isFieldAccessExpr() && target.asFieldAccessExpr().getScope().isThisExpr()
                && target.asFieldAccessExpr().getNameAsString().equals(name)
                || target.isNameExpr() && target.asNameExpr().getNameAsString().equals(name);
    }

    private boolean isImmutable(Expression value) {
        Expression unwrapped = Nodes.unwrap(value);
        return unwrapped.isMethodCallExpr()
                && IMMUTABLE_FACTORIES.contains(unwrapped.asMethodCallExpr().getNameAsString());
    }
}
