package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * hashCode() сущности считается по id с @GeneratedValue: до сохранения id равен null, после - получает значение
 * из базы, и хеш меняется. Сущность, положенная в HashSet до save(), в нем больше не находится: contains и
 * remove ее не видят, а в коллекции-связи появляются дубли
 */
@Component
public class EntityHashCodeOnGeneratedIdRule extends AbstractRule {
    private static final String ENTITY = "Entity";
    private static final String ID = "Id";
    private static final String GENERATED_VALUE = "GeneratedValue";
    private static final String HASH_CODE = "hashCode";
    private static final String GETTER_PREFIX = "get";

    @Override
    public String code() {
        return RuleCodes.ENTITY_HASH_CODE_ON_GENERATED_ID_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует сущности JPA и ищет hashCode(), который считается по id с @GeneratedValue";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration entity : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (!Annotations.has(entity, ENTITY)) {
                continue;
            }
            Set<String> generatedIds = generatedIds(entity);
            Optional<MethodDeclaration> hashCode = entity.getMethodsByName(HASH_CODE).stream()
                    .filter(method -> method.getParameters().isEmpty() && method.getBody().isPresent())
                    .findFirst();
            if (generatedIds.isEmpty() || hashCode.isEmpty()) {
                continue;
            }
            usedId(hashCode.get(), generatedIds).ifPresent(id -> violations.add(violation(sourceFile, hashCode.get(),
                    "hashCode() сущности '" + entity.getNameAsString() + "' считается по '" + id + "', а его"
                            + " значение задает база при сохранении: хеш меняется после save(), и сущность,"
                            + " положенная в HashSet раньше, в нем уже не находится; верните постоянное значение"
                            + " (getClass().hashCode()) либо считайте по неизменяемому бизнес-ключу")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private Set<String> generatedIds(ClassOrInterfaceDeclaration entity) {
        return entity.getFields().stream()
                .filter(field -> Annotations.has(field, ID) && Annotations.has(field, GENERATED_VALUE))
                .flatMap(field -> field.getVariables().stream())
                .map(VariableDeclarator::getNameAsString)
                .collect(Collectors.toSet());
    }

    // Objects.hash(id), id.hashCode(), this.id, getId()
    private Optional<String> usedId(MethodDeclaration hashCode, Set<String> ids) {
        Optional<String> byName = hashCode.findAll(NameExpr.class).stream()
                .map(NameExpr::getNameAsString)
                .filter(ids::contains)
                .findFirst();
        if (byName.isPresent()) {
            return byName;
        }
        Optional<String> byField = hashCode.findAll(FieldAccessExpr.class).stream()
                .filter(access -> access.getScope().isThisExpr())
                .map(FieldAccessExpr::getNameAsString)
                .filter(ids::contains)
                .findFirst();
        if (byField.isPresent()) {
            return byField;
        }
        return hashCode.findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getArguments().isEmpty()
                        && call.getScope().filter(scope -> !scope.isThisExpr()).isEmpty())
                .map(MethodCallExpr::getNameAsString)
                .filter(name -> name.startsWith(GETTER_PREFIX) && name.length() > GETTER_PREFIX.length())
                .map(name -> name.substring(GETTER_PREFIX.length(), GETTER_PREFIX.length() + 1).toLowerCase(Locale.ROOT)
                        + name.substring(GETTER_PREFIX.length() + 1))
                .filter(ids::contains)
                .findFirst();
    }
}
