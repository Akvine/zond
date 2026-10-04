package ru.akvine.zond.rules.logical;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.rules.AbstractSqlWithoutWhereRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.regex.Pattern;

@Component
public class CheckDeleteWithoutWhereRule extends AbstractSqlWithoutWhereRule {
    // delete from orders, DELETE FROM Order o: строка должна быть запросом целиком,
    // иначе под правило попадут сообщения вида "delete from list failed"
    private static final Pattern DELETE = Pattern.compile(
            "^delete\\s+from\\s+[\\w.\"`]+(\\s+(as\\s+)?\\w+)?\\s*;?$", Pattern.CASE_INSENSITIVE);

    @Override
    public String code() {
        return RuleCodes.CHECK_DELETE_WITHOUT_WHERE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет запросы DELETE без WHERE";
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
        return DELETE;
    }

    @Override
    protected String message(String sql) {
        return "DELETE без WHERE в '" + sql + "': запрос удалит все строки таблицы; добавьте условие, а если"
                + " очистка всей таблицы задумана - сделайте это явно (TRUNCATE, deleteAllInBatch)";
    }
}
