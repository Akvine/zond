package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.MethodDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;

@Component
public class CheckNativePagingWithoutCountQueryRule extends AbstractRule {
    private static final String PAGE = "Page";
    private static final String COUNT_QUERY = "countQuery";

    @Override
    public String code() {
        return RuleCodes.CHECK_NATIVE_PAGING_WITHOUT_COUNT_QUERY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет нативные запросы, которые возвращают Page без countQuery";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodDeclaration.class).stream()
                .filter(method -> PAGE.equals(LocalTypes.typeName(method.getType())))
                .filter(method -> Queries.find(method)
                        .filter(query -> Queries.isNative(query) && Queries.member(query, COUNT_QUERY).isEmpty())
                        .isPresent())
                .map(method -> violation(sourceFile, method,
                        "Нативный запрос метода '" + method.getNameAsString() + "' возвращает Page без countQuery:"
                                + " запрос для подсчета строк Spring Data выводит сам, и на запросах с JOIN,"
                                + " GROUP BY или подзапросом он получается неверным либо падает; задайте countQuery"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }
}
