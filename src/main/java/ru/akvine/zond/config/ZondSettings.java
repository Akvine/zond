package ru.akvine.zond.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Настройки из внешнего файла: app.properties в рабочей директории либо файл из --config=<путь>.
 * Файл необязателен и читается в UTF-8, чтобы в путях можно было использовать кириллицу.
 */
@Component
@PropertySource(value = "file:${config:./app.properties}", ignoreResourceNotFound = true, encoding = "UTF-8")
public class ZondSettings {
    private final String reportPath;
    private final String disabledRules;
    private final String minLevel;

    public ZondSettings(
            @Value("${zond.report.path:}") String reportPath,
            @Value("${zond.rules.disabled:}") String disabledRules,
            @Value("${zond.rules.min-level:}") String minLevel) {
        this.reportPath = reportPath;
        this.disabledRules = disabledRules;
        this.minLevel = minLevel;
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
}
