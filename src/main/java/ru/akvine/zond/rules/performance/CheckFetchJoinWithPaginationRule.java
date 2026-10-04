package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.body.MethodDeclaration;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CheckFetchJoinWithPaginationRule extends AbstractRule {
    private static final Set<String> PAGING_TYPES = Set.of("Pageable", "PageRequest", "Page", "Slice");

    // join fetch o.items - имя связи после точки
    private static final Pattern FETCHED_RELATION =
            Pattern.compile("join\\s+fetch\\s+\\w+\\.(\\w+)", Pattern.CASE_INSENSITIVE);

    // Типы не разрешаем: о том, что связь - коллекция, судим по множественному числу в имени
    private static final Pattern COLLECTION_NAME = Pattern.compile(".*(s|List|Set|Collection)$");

    @Override
    public String code() {
        return RuleCodes.CHECK_FETCH_JOIN_WITH_PAGINATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет JOIN FETCH коллекции в запросах с постраничной выдачей";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            Optional<String> query = Queries.find(method).flatMap(Queries::text);
            if (query.isEmpty() || !isPaged(method)) {
                continue;
            }
            Matcher fetched = FETCHED_RELATION.matcher(query.get());
            while (fetched.find()) {
                String relation = fetched.group(1);
                if (COLLECTION_NAME.matcher(relation).matches()) {
                    violations.add(violation(sourceFile, method,
                            "JOIN FETCH коллекции '" + relation + "' в запросе метода '" + method.getNameAsString()
                                    + "' с постраничной выдачей: Hibernate не может ограничить такую выборку в БД,"
                                    + " загружает все строки и режет страницу в памяти; выбирайте страницу"
                                    + " идентификаторов отдельным запросом либо используйте @BatchSize"));
                    break;
                }
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    private boolean isPaged(MethodDeclaration method) {
        return PAGING_TYPES.contains(LocalTypes.typeName(method.getType()))
                || method.getParameters().stream()
                .anyMatch(parameter -> PAGING_TYPES.contains(LocalTypes.typeName(parameter.getType())));
    }
}
