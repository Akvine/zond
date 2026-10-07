package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.JpaProperties;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.Queries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class UnknownPropertyInQueryMethodRule extends AbstractRule implements ProjectRule {
    // findByName, countDistinctByStatus, findTop10ByOrderByCreatedAtDesc
    private static final Pattern DERIVED = Pattern.compile(
            "^(?:find|read|get|query|search|stream|count|exists|delete|remove)(?:[A-Z]\\w*?)??By([A-Z]\\w*)$");
    private static final String ORDER_BY = "OrderBy";
    private static final Pattern PART_SEPARATOR = Pattern.compile("(?:And|Or)(?=\\p{Lu})");
    private static final Pattern ORDER_SEPARATOR = Pattern.compile("(?<=Asc|Desc)(?=\\p{Lu})");
    private static final Pattern DIRECTION = Pattern.compile("(Asc|Desc)$");

    // Слова условия, которые стоят после имени свойства; длинные идут раньше коротких
    private static final Pattern KEYWORD = Pattern.compile(
            "(IsNotNull|IsNotEmpty|IsNotIn|IsNotLike|IsNull|IsEmpty|IsTrue|IsFalse|IsNot|IsIn|IsLike|IsBetween|IsBefore|IsAfter"
                    + "|IsLessThanEqual|IsLessThan|IsGreaterThanEqual|IsGreaterThan|IsStartingWith|IsEndingWith|IsContaining"
                    + "|IsNear|IsWithin|NotContaining|NotContains|NotNull|NotEmpty|NotLike|NotIn|LessThanEqual|LessThan"
                    + "|GreaterThanEqual|GreaterThan|StartingWith|StartsWith|EndingWith|EndsWith|Containing|Contains"
                    + "|MatchesRegex|Matches|Regex|Between|Before|After|Exists|Equals|Like|Null|Empty|True|False"
                    + "|Near|Within|Not|In|Is)$");
    private static final Pattern IGNORE_CASE = Pattern.compile("(AllIgnoreCase|AllIgnoringCase|IgnoreCase|IgnoringCase)$");
    private static final String QUERY_ANNOTATION_SUFFIX = "Query";

    @Override
    public String code() {
        return RuleCodes.UNKNOWN_PROPERTY_IN_QUERY_METHOD_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует репозитории Spring Data и ищет методы, в имени которых названо свойство, которого нет у сущности";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration repository : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                Optional<ClassOrInterfaceDeclaration> entity = entityOf(repository, classes);
                if (entity.isEmpty()) {
                    continue;
                }
                for (MethodDeclaration method : repository.getMethods()) {
                    // Запрос задан явно либо метод написан вручную: имя метода ни на что не влияет
                    boolean hasOwnQuery = method.getBody().isPresent() || method.getAnnotations().stream()
                            .anyMatch(annotation -> annotation.getNameAsString().endsWith(QUERY_ANNOTATION_SUFFIX));
                    Matcher derived = DERIVED.matcher(method.getNameAsString());
                    if (hasOwnQuery || !derived.matches()) {
                        continue;
                    }
                    for (String property : propertiesOf(derived.group(1))) {
                        if (!JpaProperties.exists(entity.get(), property, classes)) {
                            violations.add(violation(sourceFile, method,
                                    "Метод '" + method.getNameAsString() + "' обращается к свойству '"
                                            + uncapitalize(property) + "', которого нет у сущности '"
                                            + entity.get().getNameAsString() + "': Spring Data не сможет построить"
                                            + " запрос, и приложение не запустится; исправьте имя метода или свойства"));
                        }
                    }
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

    // interface OrderRepository extends JpaRepository<Order, Long> -> Order
    private Optional<ClassOrInterfaceDeclaration> entityOf(ClassOrInterfaceDeclaration repository, ProjectClasses classes) {
        if (!Queries.isRepositoryInterface(repository)) {
            return Optional.empty();
        }
        return repository.getExtendedTypes().stream()
                .map(ClassOrInterfaceType::getTypeArguments)
                .flatMap(Optional::stream)
                .filter(arguments -> !arguments.isEmpty() && arguments.get(0).isClassOrInterfaceType())
                .map(arguments -> arguments.get(0).asClassOrInterfaceType().getNameAsString())
                .findFirst()
                .flatMap(classes::find)
                .filter(type -> !type.isInterface());
    }

    // NameAndStatusInOrderByCreatedAtDesc -> Name, Status, CreatedAt
    private List<String> propertiesOf(String predicate) {
        List<String> properties = new ArrayList<>();
        int orderBy = predicate.indexOf(ORDER_BY);
        // Order может быть началом имени свойства (OrderId): сортировка - только если дальше идет заглавная буква
        boolean sorted = orderBy >= 0 && (orderBy == 0 || predicate.length() > orderBy + ORDER_BY.length());
        String conditions = sorted ? predicate.substring(0, orderBy) : predicate;
        for (String part : PART_SEPARATOR.split(conditions)) {
            String property = IGNORE_CASE.matcher(part).replaceFirst("");
            String withoutKeyword = KEYWORD.matcher(property).replaceFirst("");
            // Слово условия может быть и самим свойством: findByActiveTrue и findByTrue
            if (!withoutKeyword.isEmpty()) {
                properties.add(withoutKeyword);
            }
        }
        if (sorted) {
            for (String order : ORDER_SEPARATOR.split(predicate.substring(orderBy + ORDER_BY.length()))) {
                String property = DIRECTION.matcher(order).replaceFirst("");
                if (!property.isEmpty()) {
                    properties.add(property);
                }
            }
        }
        return properties;
    }

    private String uncapitalize(String name) {
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
