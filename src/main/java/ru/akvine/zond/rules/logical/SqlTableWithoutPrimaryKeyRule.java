package ru.akvine.zond.rules.logical;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.DbSchema;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class SqlTableWithoutPrimaryKeyRule extends AbstractContextRule {
    // Аналитические базы задают ключ иначе: UNIQUE KEY(id) / DUPLICATE KEY / AGGREGATE KEY у Doris и StarRocks,
    // ENGINE = MergeTree ORDER BY ... у ClickHouse. Первичного ключа в смысле OLTP у таких таблиц не бывает
    private static final Pattern OWN_KEY_MODEL = Pattern.compile(
            "(?i)\\b(unique|duplicate|aggregate)\\s+key\\s*\\(|\\bengine\\s*=\\s*\\w*mergetree");
    private static final String STATEMENT_END = ";";
    // Ищем конец оператора не дальше этого числа строк
    private static final int MAX_STATEMENT_LINES = 300;

    @Override
    public String code() {
        return RuleCodes.SQL_TABLE_WITHOUT_PRIMARY_KEY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует миграции (SQL и журналы Liquibase) и ищет таблицы, у которых нет первичного ключа";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        Map<Path, TextFile> files = context.textFiles().stream()
                .collect(Collectors.toMap(TextFile::path, Function.identity(), (first, second) -> first));
        List<Violation> violations = new ArrayList<>();
        // Смотрим на итог всех миграций: ключ могли добавить отдельной командой позже
        for (DbSchema.Table table : DbSchema.of(context).tables()) {
            // У таблицы-связки многие-ко-многим строку определяет пара внешних ключей: отдельный ключ ей не обязателен
            if (table.hasPrimaryKey() || table.isOpaque() || table.isLinkTable() || table.columns().isEmpty()
                    || hasOwnKeyModel(files.get(table.file()), table.line())) {
                continue;
            }
            violations.add(violation(table.file(), table.line(),
                    "У таблицы '" + table.name() + "' нет первичного ключа: строку нельзя однозначно найти,"
                            + " изменить или удалить, возможны полные дубликаты, а логическая репликация таких"
                            + " таблиц не работает; добавьте первичный ключ"));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    // Текст CREATE TABLE от его строки до точки с запятой
    private boolean hasOwnKeyModel(TextFile file, int line) {
        if (file == null || line < 1 || line > file.lines().size()) {
            return false;
        }
        StringBuilder statement = new StringBuilder();
        for (int index = line - 1; index < file.lines().size() && index < line - 1 + MAX_STATEMENT_LINES; index++) {
            String text = file.lines().get(index);
            statement.append(text).append('\n');
            if (text.contains(STATEMENT_END)) {
                break;
            }
        }
        return OWN_KEY_MODEL.matcher(statement).find();
    }
}
