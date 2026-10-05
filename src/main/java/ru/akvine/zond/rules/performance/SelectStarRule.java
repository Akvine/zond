package ru.akvine.zond.rules.performance;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.StringLiterals;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.List;
import java.util.regex.Pattern;

@Component
public class SelectStarRule extends AbstractRule {
    // select * from, select t.* from; count(*) сюда не попадает
    private static final Pattern SELECT_STAR = Pattern.compile(
            "^\\s*select\\s+(distinct\\s+)?(\\w+\\.)?\\*\\s*(,|from\\b)", Pattern.CASE_INSENSITIVE);

    @Override
    public String code() {
        return RuleCodes.SELECT_STAR_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет SQL-запросы с SELECT *";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return StringLiterals.findComplete(sourceFile.unit()).stream()
                .filter(literal -> SELECT_STAR.matcher(literal.text()).find())
                .filter(literal -> !TestClasses.isInside(literal.node()))
                .map(literal -> violation(sourceFile, literal.node(),
                        "SELECT * в запросе: из БД читаются все колонки, включая ненужные и большие, а после"
                                + " добавления колонки в таблицу разбор результата может сломаться;"
                                + " перечислите нужные колонки"))
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
