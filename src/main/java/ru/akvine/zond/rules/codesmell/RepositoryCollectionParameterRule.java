package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Queries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class RepositoryCollectionParameterRule extends AbstractRule {
    private static final Set<String> CONCRETE_COLLECTIONS =
            Set.of("List", "Set", "ArrayList", "LinkedList", "HashSet", "LinkedHashSet", "TreeSet");
    private static final String OBJECT = "Object";
    // Значения, которые подставляют в условие запроса
    private static final Set<String> VALUE_TYPES = Set.of(
            "String", "Long", "Integer", "Short", "Byte", "UUID", "BigDecimal", "BigInteger", "Double", "Float",
            "Boolean", "Character", "LocalDate", "LocalDateTime", "Instant", "OffsetDateTime", "ZonedDateTime",
            "Date", "Timestamp");
    private static final Pattern WRITES_ROWS = Pattern.compile(
            "(insert|save|create|add|batch|bulk|upsert|merge|persist|store|write|replace|put|update|import|register)"
                    + "\\w*");
    private static final Pattern IN_KEYWORD = Pattern.compile("By\\w*In($|[A-Z])");
    private static final Pattern IN_CLAUSE = Pattern.compile("\\bin\\s*[(:?#]", Pattern.CASE_INSENSITIVE);

    @Override
    public String code() {
        return RuleCodes.REPOSITORY_COLLECTION_PARAMETER_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет методы репозиториев, которые принимают List или Set вместо Collection";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.findAll(ClassOrInterfaceDeclaration.class)) {
            if (!Queries.isRepositoryInterface(type)) {
                continue;
            }
            for (MethodDeclaration method : type.getMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    String parameterType = LocalTypes.typeName(parameter.getType());
                    if (CONCRETE_COLLECTIONS.contains(parameterType) && !isOrderedInput(method, parameter)) {
                        violations.add(violation(sourceFile, parameter,
                                "Параметр '" + parameter.getNameAsString() + "' метода '" + method.getNameAsString()
                                        + "' объявлен как " + parameterType + ": запросу порядок и уникальность"
                                        + " значений не важны, а вызывающему придется перекладывать свою"
                                        + " коллекцию в " + parameterType + "; объявите параметр как Collection"));
                    }
                }
            }
        }
        return violations;
    }

    // Не набор значений для IN (...), а данные, у которых порядок и повторы значимы: строки для записи
    // (insertList(List<Relation> rows), batchCreate(List<Card> cards)) либо позиционные аргументы запроса
    // (query(String sql, List<Object> args))
    private boolean isOrderedInput(MethodDeclaration method, Parameter parameter) {
        Optional<String> element = parameter.getType().toClassOrInterfaceType()
                .flatMap(ClassOrInterfaceType::getTypeArguments)
                .filter(arguments -> !arguments.isEmpty())
                .map(arguments -> LocalTypes.typeName(arguments.get(0)));
        if (element.isEmpty() || OBJECT.equals(element.get())) {
            return true;
        }
        String name = method.getNameAsString();
        if (VALUE_TYPES.contains(element.get()) || !WRITES_ROWS.matcher(name).matches()) {
            return false;
        }
        // updateStatusByOwnerIn(List<Owner> owners) и @Query("... where o.owner in :owners") - все же значения для IN
        boolean inQuery = method.getAnnotations().stream()
                .anyMatch(annotation -> IN_CLAUSE.matcher(annotation.toString()).find());
        return !IN_KEYWORD.matcher(name).find() && !inQuery;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }
}
