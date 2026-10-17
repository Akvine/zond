package ru.akvine.zond.rules.files;

import lombok.experimental.UtilityClass;
import ru.akvine.zond.enums.TextFileType;
import ru.akvine.zond.models.TextFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Виды текстовых файлов проекта и разбор тех из них, что устроены как "ключ = значение"
 */
@UtilityClass
public class TextFiles {
    private static final Set<String> SAMPLE_DIRECTORIES = Set.of("samples", "fixtures", "testdata", "test-data");
    // multi-tenant-demo-backups, old-schema-files, sample_data, db-dumps: копии базы и устаревшие схемы
    private static final Pattern COPY_DIRECTORY = Pattern.compile("(?i).*(backup|dump|sample|archive).*|old[-_].*|.*[-_]old");
    private static final int SAMPLE_DEPTH = 3;
    // key=value, key: value, key value
    private static final Pattern PROPERTY_LINE = Pattern.compile("^\\s*([^=:\\s]+)\\s*[=:]?\\s*(.*)$");

    /**
     * Строка файла свойств
     */
    public record Entry(String key, String value, int line) {
    }

    /**
     * @return true для миграций базы данных: SQL-файла либо журнала Liquibase в XML или YAML
     */
    public boolean isMigration(TextFile file) {
        boolean migrationType = file.type() == TextFileType.SQL
                || file.type() == TextFileType.LIQUIBASE_XML
                || file.type() == TextFileType.LIQUIBASE_YAML;
        return migrationType && !isSampleData(file.path());
    }

    // samples/schemas/changelog.xml - входные данные или пример, а не миграция проекта; demo-backups/bk_tenant.sql -
    // дамп базы, его CREATE TABLE описывает старую схему. Смотрим только ближайшие каталоги: проекты могут
    // лежать в папке с любым именем
    private boolean isSampleData(Path path) {
        Path parent = path.getParent();
        for (int depth = 0; parent != null && depth < SAMPLE_DEPTH; depth++, parent = parent.getParent()) {
            String name = parent.getFileName() == null ? "" : parent.getFileName().toString();
            if (SAMPLE_DIRECTORIES.contains(name) || COPY_DIRECTORY.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    public boolean isPom(TextFile file) {
        return file.type() == TextFileType.POM;
    }

    public boolean isGradle(TextFile file) {
        return file.type() == TextFileType.GRADLE;
    }

    public boolean isDockerfile(TextFile file) {
        return file.type() == TextFileType.DOCKERFILE;
    }

    public boolean isCompose(TextFile file) {
        return file.type() == TextFileType.COMPOSE;
    }

    public boolean isMessages(TextFile file) {
        return file.type() == TextFileType.MESSAGES;
    }

    public boolean isLogConfig(TextFile file) {
        return file.type() == TextFileType.LOGBACK || file.type() == TextFileType.LOG4J2;
    }

    public boolean isKubernetes(TextFile file) {
        return file.type() == TextFileType.KUBERNETES;
    }

    public boolean isCi(TextFile file) {
        return file.type() == TextFileType.GITLAB_CI || file.type() == TextFileType.GITHUB_WORKFLOW;
    }

    /**
     * @return записи файла .properties по порядку, вместе с повторами ключей
     */
    public List<Entry> properties(List<String> lines) {
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
