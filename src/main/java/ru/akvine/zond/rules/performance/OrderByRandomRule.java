package ru.akvine.zond.rules.performance;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.QueryTexts;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * ORDER BY RAND() / RANDOM() / NEWID(): чтобы выбрать несколько случайных строк, база присваивает случайное
 * число каждой строке таблицы и сортирует ее целиком
 */
@Component
public class OrderByRandomRule extends AbstractContextRule {
    private static final Pattern ORDER_BY_RANDOM = Pattern.compile(
            "(?i)\\border\\s+by\\s+(rand\\s*\\(\\s*\\)|random\\s*\\(\\s*\\)|newid\\s*\\(\\s*\\)|dbms_random\\.value)");

    @Override
    public String code() {
        return RuleCodes.ORDER_BY_RANDOM_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует запросы в коде и мапперах MyBatis и ищет ORDER BY RAND() / RANDOM() / NEWID()";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        return QueryTexts.of(context).stream()
                .flatMap(query -> {
                    Matcher matcher = ORDER_BY_RANDOM.matcher(query.text());
                    return matcher.find()
                            ? Stream.of(violation(query.file(), query.line(),
                            "'" + matcher.group().replaceAll("\\s+", " ") + "': чтобы выбрать случайные строки,"
                                    + " база присваивает случайное число каждой строке таблицы и сортирует ее целиком,"
                                    + " и время растет вместе с таблицей; выберите случайные id в приложении"
                                    + " либо используйте TABLESAMPLE или выборку по случайному смещению ключа"))
                            : Stream.empty();
                })
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }
}
