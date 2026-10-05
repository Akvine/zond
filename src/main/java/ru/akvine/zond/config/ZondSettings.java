package ru.akvine.zond.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.FileKind;
import ru.akvine.zond.rules.flow.FlowLibrary;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Настройки из внешнего файла: app.properties в рабочей директории либо файл из --config=<путь>.
 * Файл необязателен и читается в UTF-8, чтобы в путях можно было использовать кириллицу.
 */
@Component
@PropertySource(value = "file:${config:./app.properties}", ignoreResourceNotFound = true, encoding = "UTF-8")
public class ZondSettings {
    private static final String CLASSPATH_SEPARATOR = "[;,]";
    private static final String MIN_CONFIDENCE = "zond.rules.min-confidence";
    private static final String PROGRESS_PERCENT = "zond.progress.percent";
    private static final String FLOW_LIBRARY = "zond.flow.library";
    private static final String AUTO_CLASSPATH = "zond.scan.auto-classpath";
    private static final String MAVEN_REPOSITORY = "zond.scan.maven-repository";
    private static final String REPORT_CONFIDENCE = "zond.report.confidence";
    private static final String FALSE = "false";

    private final String reportPath;
    private final String disabledRules;
    private final String minLevel;
    private final String skipTests;
    private final String classpath;
    private final String exclude;
    private final String threads;
    private final Environment environment;

    public ZondSettings(
            @Value("${zond.report.path:}") String reportPath,
            @Value("${zond.rules.disabled:}") String disabledRules,
            @Value("${zond.rules.min-level:}") String minLevel,
            @Value("${zond.scan.skip-tests:}") String skipTests,
            @Value("${zond.scan.classpath:}") String classpath,
            @Value("${zond.scan.exclude:}") String exclude,
            @Value("${zond.scan.threads:}") String threads,
            Environment environment) {
        this.reportPath = reportPath;
        this.disabledRules = disabledRules;
        this.minLevel = minLevel;
        this.skipTests = skipTests;
        this.classpath = classpath;
        this.exclude = exclude;
        this.threads = threads;
        this.environment = environment;
        // Свой справочник библиотек для анализа потока данных дополняет встроенный
        String library = environment.getProperty(FLOW_LIBRARY, "").trim();
        if (!library.isEmpty()) {
            FlowLibrary.extend(Path.of(library));
        }
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

    /**
     * @return число потоков сканирования как задано в настройках либо пустая строка
     */
    public String threads() {
        return threads;
    }

    /**
     * @return значение настройки zond.scan.&lt;вид файлов&gt; как задано либо пустая строка
     */
    public String scanEnabled(FileKind kind) {
        return environment.getProperty(kind.property(), "");
    }

    /**
     * @return наименьшая уверенность находки, попадающей в отчет, как задано либо пустая строка
     */
    public String minConfidence() {
        return environment.getProperty(MIN_CONFIDENCE, "");
    }

    /**
     * @return true, если при сканировании рядом со счетчиком правил нужно показывать процент.
     * Выключается только явным false
     */
    public boolean progressPercent() {
        return !FALSE.equalsIgnoreCase(environment.getProperty(PROGRESS_PERCENT, "").trim());
    }

    /**
     * @return true, если в отчете нужно показывать уверенность находок. Выключается только явным false;
     * на отбор находок по zond.rules.min-confidence это не влияет
     */
    public boolean reportConfidence() {
        return !FALSE.equalsIgnoreCase(environment.getProperty(REPORT_CONFIDENCE, "").trim());
    }

    /**
     * @return true, если библиотеки проекта нужно искать самому по его pom.xml. Выключается только явным false
     */
    public boolean autoClasspath() {
        return !FALSE.equalsIgnoreCase(environment.getProperty(AUTO_CLASSPATH, "").trim());
    }

    /**
     * @return локальный репозиторий Maven, если он задан настройкой; иначе берется ~/.m2/repository
     */
    public Optional<Path> mavenRepository() {
        String path = environment.getProperty(MAVEN_REPOSITORY, "").trim();
        return path.isEmpty() ? Optional.empty() : Optional.of(Path.of(path));
    }

    public static List<Path> parseClasspath(String classpath) {
        return Arrays.stream(classpath.split(CLASSPATH_SEPARATOR))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .map(Path::of)
                .toList();
    }
}
