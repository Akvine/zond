package ru.akvine.zond.rules.files;

import lombok.experimental.UtilityClass;
import ru.akvine.zond.enums.TextFileType;
import ru.akvine.zond.models.TestPaths;
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
    // examples/, demo/, docker-compose/clickhouse/init/: схема примера или чужой базы для стенда, а не схема продукта
    private static final Set<String> SAMPLE_DIRECTORIES = Set.of(
            "samples", "fixtures", "testdata", "test-data", "example", "examples", "demo", "demos", "docker-compose",
            "thirdparties", "third-party", "third_party");
    // multi-tenant-demo-backups, old-schema-files, sample_data, db-dumps: копии базы и устаревшие схемы
    private static final Pattern COPY_DIRECTORY = Pattern.compile("(?i).*(backup|dump|sample|archive).*|old[-_].*|.*[-_]old");
    private static final int SAMPLE_DEPTH = 3;
    private static final Set<String> DROP_DIRECTORIES = Set.of("drop");
    private static final Pattern DROP_SCRIPT = Pattern.compile("(?i)(.*\\.)?drop([-._].*)?\\.sql");
    private static final Pattern DEMO_DIRECTORY =
            Pattern.compile("(?i)examples?|demos?|.*[-_]demo|demo[-_].*|quickstart");
    private static final Set<String> BUILD_IMAGE_DIRECTORIES =
            Set.of("compilation", ".devcontainer", "devcontainer", "build-env");
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
        return migrationType && !isSampleData(file.path()) && !isDropScript(file.path());
    }

    // db/drop/activiti.mysql.drop.engine.sql - удаление схемы при деинсталляции, а не шаг миграции: его DROP TABLE
    // не применяется к рабочей базе по порядку. V3__drop_column.sql у Flyway - обычная миграция
    private boolean isDropScript(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        return isNearestDirectory(path, DROP_DIRECTORIES) || DROP_SCRIPT.matcher(name).matches();
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

    // Образы, docker-compose и манифесты тестовых стендов (ci/tests, src/test), примеров и демо в рабочую среду
    // не попадают. Образ для сборки (docker/compilation, .devcontainer) приложение не запускает
    public boolean isDockerfile(TextFile file) {
        return file.type() == TextFileType.DOCKERFILE && !TestPaths.isTest(file.path()) && !isDemo(file.path())
                && !isNearestDirectory(file.path(), BUILD_IMAGE_DIRECTORIES);
    }

    public boolean isCompose(TextFile file) {
        return file.type() == TextFileType.COMPOSE && !TestPaths.isTest(file.path()) && !isDemo(file.path());
    }

    // docker/runtime/docker-compose-demo/example/...: пример или демо. Смотрим только ближайшие каталоги -
    // в пути пакета Java com/example/demo эти имена ничего не значат
    private boolean isDemo(Path path) {
        Path parent = path.getParent();
        for (int depth = 0; parent != null && depth < SAMPLE_DEPTH + 1; depth++, parent = parent.getParent()) {
            String name = parent.getFileName() == null ? "" : parent.getFileName().toString();
            if (DEMO_DIRECTORY.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    private boolean isNearestDirectory(Path path, Set<String> names) {
        Path parent = path.getParent();
        for (int depth = 0; parent != null && depth < SAMPLE_DEPTH; depth++, parent = parent.getParent()) {
            if (parent.getFileName() != null && names.contains(parent.getFileName().toString())) {
                return true;
            }
        }
        return false;
    }

    public boolean isMessages(TextFile file) {
        return file.type() == TextFileType.MESSAGES;
    }

    public boolean isLogConfig(TextFile file) {
        return file.type() == TextFileType.LOGBACK || file.type() == TextFileType.LOG4J2;
    }

    public boolean isKubernetes(TextFile file) {
        return file.type() == TextFileType.KUBERNETES && !TestPaths.isTest(file.path());
    }

    public boolean isCi(TextFile file) {
        return file.type() == TextFileType.GITLAB_CI || file.type() == TextFileType.GITHUB_WORKFLOW;
    }

    /**
     * @return записи файла .properties по порядку, вместе с повторами ключей
     */
    public List<Entry> properties(List<String> lines) {
        List<Entry> entries = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            // Значение, перенесенное на следующие строки через \, - одна запись: продолжение дописывается к ней
            int first = index;
            StringBuilder logical = new StringBuilder();
            while (endsWithContinuation(line) && index + 1 < lines.size()) {
                logical.append(line, 0, line.length() - 1);
                line = lines.get(++index).trim();
            }
            logical.append(line);
            Matcher matcher = PROPERTY_LINE.matcher(logical);
            if (matcher.matches()) {
                entries.add(new Entry(matcher.group(1), matcher.group(2).trim(), first + 1));
            }
        }
        return entries;
    }

    // Нечетное число \ в конце строки - перенос; четное - экранированные обратные черты самого значения
    private boolean endsWithContinuation(String line) {
        int slashes = 0;
        for (int index = line.length() - 1; index >= 0 && line.charAt(index) == '\\'; index--) {
            slashes++;
        }
        return slashes % 2 == 1;
    }
}
