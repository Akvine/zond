package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
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
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckInternalCollectionExposureRule extends AbstractRule {
    private static final Set<String> MUTABLE_TYPES = Set.of(
            "List", "Set", "Map", "Collection", "ArrayList", "LinkedList", "HashSet", "HashMap", "TreeMap", "Date");

    // Сущностям и классам с данными (DTO) отдавать коллекцию как есть положено по их назначению
    private static final Set<String> DATA_CLASS_ANNOTATIONS =
            Set.of("Entity", "Embeddable", "MappedSuperclass", "Data", "Value", "Getter", "ConfigurationProperties");

    // Коллекция уже неизменяема
    private static final Set<String> IMMUTABLE_FACTORIES = Set.of(
            "of", "copyOf", "emptyList", "emptySet", "emptyMap", "unmodifiableList", "unmodifiableSet",
            "unmodifiableMap", "unmodifiableCollection", "toList");

    @Override
    public String code() {
        return RuleCodes.CHECK_INTERNAL_COLLECTION_EXPOSURE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет геттеры, которые отдают наружу внутреннюю изменяемую коллекцию или массив";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (type.isInterface() || Annotations.hasAny(type, DATA_CLASS_ANNOTATIONS)) {
                continue;
            }
            for (MethodDeclaration method : type.getMethods()) {
                if (method.isPrivate() || method.isStatic() || !method.getParameters().isEmpty()) {
                    continue;
                }
                findReturnedField(method)
                        .flatMap(field -> LocalTypes.findField(method, field))
                        .filter(this::isMutable)
                        .ifPresent(field -> violations.add(violation(sourceFile, method,
                                "Метод '" + method.getNameAsString() + "' отдает внутреннее поле '"
                                        + field.getNameAsString() + "' как есть: вызывающий код сможет менять"
                                        + " состояние объекта в обход его методов; верните копию или"
                                        + " неизменяемое представление: List.copyOf(...),"
                                        + " Collections.unmodifiableList(...)")));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    // Тело метода - единственный оператор return field; либо return this.field;
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
        boolean immutableValue = field.getInitializer()
                .map(Nodes::unwrap)
                .filter(Expression::isMethodCallExpr)
                .filter(initializer -> IMMUTABLE_FACTORIES.contains(initializer.asMethodCallExpr().getNameAsString()))
                .isPresent();
        return mutableType && !immutableValue;
    }
}
