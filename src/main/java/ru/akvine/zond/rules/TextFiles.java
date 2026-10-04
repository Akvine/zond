package ru.akvine.zond.rules;

import lombok.experimental.UtilityClass;
import ru.akvine.zond.enums.TextFileType;
import ru.akvine.zond.models.TextFile;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Виды текстовых файлов проекта и разбор тех из них, что устроены как "ключ = значение"
 */
@UtilityClass
class TextFiles {
    // key=value, key: value, key value
    private static final Pattern PROPERTY_LINE = Pattern.compile("^\\s*([^=:\\s]+)\\s*[=:]?\\s*(.*)$");

    /**
     * Строка файла свойств
     */
    record Entry(String key, String value, int line) {
    }

    /**
     * @return true для миграций базы данных: SQL-файла либо журнала Liquibase в XML или YAML
     */
    boolean isMigration(TextFile file) {
        return file.type() == TextFileType.SQL
                || file.type() == TextFileType.LIQUIBASE_XML
                || file.type() == TextFileType.LIQUIBASE_YAML;
    }

    boolean isPom(TextFile file) {
        return file.type() == TextFileType.POM;
    }

    boolean isGradle(TextFile file) {
        return file.type() == TextFileType.GRADLE;
    }

    boolean isDockerfile(TextFile file) {
        return file.type() == TextFileType.DOCKERFILE;
    }

    boolean isCompose(TextFile file) {
        return file.type() == TextFileType.COMPOSE;
    }

    boolean isMessages(TextFile file) {
        return file.type() == TextFileType.MESSAGES;
    }

    boolean isLogConfig(TextFile file) {
        return file.type() == TextFileType.LOGBACK || file.type() == TextFileType.LOG4J2;
    }

    boolean isKubernetes(TextFile file) {
        return file.type() == TextFileType.KUBERNETES;
    }

    boolean isCi(TextFile file) {
        return file.type() == TextFileType.GITLAB_CI || file.type() == TextFileType.GITHUB_WORKFLOW;
    }

    /**
     * @return записи файла .properties по порядку, вместе с повторами ключей
     */
    List<Entry> properties(List<String> lines) {
        List<Entry> entries = new ArrayList<>();
        boolean continued = false;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).trim();
            // Значение, перенесенное на следующую строку через \, - не новая запись
            boolean isContinuation = continued;
            continued = line.endsWith("\\");
            if (isContinuation || line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            Matcher matcher = PROPERTY_LINE.matcher(line);
            if (matcher.matches()) {
                entries.add(new Entry(matcher.group(1), matcher.group(2).trim(), index + 1));
            }
        }
        return entries;
    }
}
