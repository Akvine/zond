package ru.akvine.zond.cli;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.FileKind;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Сохраняет настройки, измененные в меню, в тот же файл, из которого они читаются при запуске
 */
@Component
public class SettingsStore {
    private static final String REPORT_PATH = "zond.report.path";
    private static final String DISABLED_RULES = "zond.rules.disabled";
    private static final String MIN_LEVEL = "zond.rules.min-level";
    private static final String SKIP_TESTS = "zond.scan.skip-tests";
    private static final String EXCLUDE = "zond.scan.exclude";
    private static final String MIN_CONFIDENCE = "zond.rules.min-confidence";
    private static final String TIME_UNIT = "zond.progress.time-unit";
    private static final String LIST_DELIMITER = ", ";

    private final Path configFile;

    public SettingsStore(@Value("${config:./app.properties}") String configFile) {
        this.configFile = Path.of(configFile);
    }

    public Path file() {
        return configFile.toAbsolutePath().normalize();
    }

    /**
     * Меняет только значения своих свойств: комментарии и прочие строки файла остаются как были.
     *
     * @throws UncheckedIOException если файл не удалось записать
     */
    public void save(SessionSettings settings) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(REPORT_PATH, settings.reportFile() == null ? "" : toPropertyValue(settings.reportFile()));
        values.put(DISABLED_RULES, settings.disabledRulesAsText());
        values.put(MIN_LEVEL, settings.getMinLevel().name());
        values.put(MIN_CONFIDENCE, settings.getMinConfidence().name());
        values.put(TIME_UNIT, settings.getTimeUnit().getCode());
        values.put(SKIP_TESTS, String.valueOf(settings.isSkipTests()));
        values.put(EXCLUDE, String.join(LIST_DELIMITER, settings.getExclusions().patterns()));
        for (FileKind kind : FileKind.values()) {
            values.put(kind.property(), String.valueOf(settings.isScanned(kind)));
        }

        try {
            List<String> lines = Files.exists(configFile)
                    ? new ArrayList<>(Files.readAllLines(configFile, StandardCharsets.UTF_8))
                    : new ArrayList<>();
            values.forEach((key, value) -> setProperty(lines, key, value));

            Path parent = configFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(configFile, lines, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Не удалось сохранить настройки в " + file(), exception);
        }
    }

    // Заменяет строку "ключ=..." либо дописывает свойство в конец файла
    private void setProperty(List<String> lines, String key, String value) {
        Pattern property = Pattern.compile("^\\s*" + Pattern.quote(key) + "\\s*[=:].*$");
        String line = key + "=" + value;
        for (int index = 0; index < lines.size(); index++) {
            if (property.matcher(lines.get(index)).matches()) {
                lines.set(index, line);
                return;
            }
        }
        lines.add(line);
    }

    // В .properties обратная косая черта - это экранирование, поэтому путь пишется через прямую
    private String toPropertyValue(Path path) {
        return path.toString().replace('\\', '/');
    }
}
