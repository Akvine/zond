package ru.akvine.zond.rules;

import lombok.experimental.UtilityClass;
import ru.akvine.zond.models.TextFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Виды текстовых файлов проекта и разбор тех из них, что устроены как "ключ = значение"
 */
@UtilityClass
class TextFiles {
    private static final String SQL_EXTENSION = ".sql";
    private static final String POM = "pom.xml";
    private static final String GRADLE_PREFIX = "build.gradle";
    private static final String DOCKERFILE = "dockerfile";
    private static final Pattern MESSAGES = Pattern.compile("^messages.*\\.properties$");

    // key=value, key: value, key value
    private static final Pattern PROPERTY_LINE = Pattern.compile("^\\s*([^=:\\s]+)\\s*[=:]?\\s*(.*)$");

    /**
     * Строка файла свойств
     */
    record Entry(String key, String value, int line) {
    }

    boolean isSql(TextFile file) {
        return lowerName(file).endsWith(SQL_EXTENSION);
    }

    boolean isPom(TextFile file) {
        return POM.equals(lowerName(file));
    }

    boolean isGradle(TextFile file) {
        return lowerName(file).startsWith(GRADLE_PREFIX);
    }

    boolean isDockerfile(TextFile file) {
        String name = lowerName(file);
        return name.equals(DOCKERFILE) || name.startsWith(DOCKERFILE + ".") || name.endsWith("." + DOCKERFILE);
    }

    boolean isMessages(TextFile file) {
        return MESSAGES.matcher(file.name()).matches();
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

    private String lowerName(TextFile file) {
        return file.name().toLowerCase(Locale.ROOT);
    }
}
