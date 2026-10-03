package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;

@Component
public class CheckFindAllWithoutPagingRule extends AbstractRule {
    private static final String FIND_ALL = "findAll";

    @Override
    public String code() {
        return RuleCodes.CHECK_FIND_ALL_WITHOUT_PAGING_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет repository.findAll() без постраничной загрузки";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // findAll(pageable), findAll(specification) и т.п. не трогаем: там выборка ограничена
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> FIND_ALL.equals(call.getNameAsString()) && call.getArguments().isEmpty())
                .filter(Repositories::isRepositoryCall)
                .filter(call -> !TestClasses.isInside(call))
                .map(call -> violation(sourceFile, call,
                        "'" + call + "' загружает таблицу целиком: с ростом данных это долгий запрос и"
                                + " OutOfMemoryError; используйте findAll(Pageable), запрос с условием"
                                + " или потоковую выборку"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }
}
