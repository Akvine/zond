package ru.akvine.zond.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Настройки из внешнего файла: app.properties в рабочей директории либо файл из --config=<путь>.
 * Файл необязателен и читается в UTF-8, чтобы в путях можно было использовать кириллицу.
 */
@Component
@PropertySource(value = "file:${config:./app.properties}", ignoreResourceNotFound = true, encoding = "UTF-8")
public class ZondSettings {
    private static final String CLASSPATH_SEPARATOR = "[;,]";

    private final String reportPath;
    private final String disabledRules;
    private final String minLevel;
    private final String skipTests;
    private final String classpath;
    private final String exclude;

    public ZondSettings(
            @Value("${zond.report.path:}") String reportPath,
            @Value("${zond.rules.disabled:}") String disabledRules,
            @Value("${zond.rules.min-level:}") String minLevel,
            @Value("${zond.scan.skip-tests:}") String skipTests,
            @Value("${zond.scan.classpath:}") String classpath,
            @Value("${zond.scan.exclude:}") String exclude) {
        this.reportPath = reportPath;
        this.disabledRules = disabledRules;
        this.minLevel = minLevel;
        this.skipTests = skipTests;
        this.classpath = classpath;
        this.exclude = exclude;
    }

    /**
     * @return true, если каталоги test проверять не нужно. Свойство без значения считается выключенным
     */
    public boolean skipTests() {
        return Boolean.parseBoolean(skipTests.trim());
    }

    /**
     * @return файл отчета или null, если отчет нужно выводить в консоль
     */
    public Path reportPath() {
        return reportPath.isBlank() ? null : Path.of(reportPath.trim());
    }

    /**
     * @return отключенные правила через запятую (коды или имена) либо пустая строка
     */
    public String disabledRules() {
        return disabledRules;
    }

    /**
     * @return имя уровня, ниже которого находки не показываются, либо пустая строка
     */
    public String minLevel() {
        return minLevel;
    }

    /**
     * @return библиотеки проверяемого проекта: jar-файлы и папки с ними, перечисленные через ";" или ","
     */
    public List<Path> classpath() {
        return parseClasspath(classpath);
    }

    /**
     * @return пути, которые не нужно сканировать: шаблоны через запятую либо пустая строка
     */
    public String exclude() {
        return exclude;
    }

    public static List<Path> parseClasspath(String classpath) {
        return Arrays.stream(classpath.split(CLASSPATH_SEPARATOR))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .map(Path::of)
                .toList();
    }
}
