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

    public ZondSettings(@Value("${zond.report.path:}") String reportPath) {
        this.reportPath = reportPath;
    }

    /**
     * @return файл отчета или null, если отчет нужно выводить в консоль
     */
    public Path reportPath() {
        return reportPath.isBlank() ? null : Path.of(reportPath.trim());
    }
}
