package ru.akvine.zond.rules;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;

import java.util.regex.Pattern;

@Component
public class CheckUpdateWithoutWhereRule extends AbstractSqlWithoutWhereRule {
    // update orders set status = ?, UPDATE Order o SET o.status = :status
    private static final Pattern UPDATE = Pattern.compile(
            "^update\\s+[\\w.\"`]+(\\s+(as\\s+)?\\w+)?\\s+set\\s+.+=.+$", Pattern.CASE_INSENSITIVE);

    @Override
    public String code() {
        return RuleCodes.CHECK_UPDATE_WITHOUT_WHERE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет запросы UPDATE без WHERE";
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    @Override
    protected Pattern statement() {
        return UPDATE;
    }

    @Override
    protected String message(String sql) {
        return "UPDATE без WHERE в '" + sql + "': запрос изменит все строки таблицы; добавьте условие";
    }
}
