package ru.akvine.zond.rules;

import lombok.experimental.UtilityClass;
import ru.akvine.zond.enums.TextFileType;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.parsers.XmlElement;
import ru.akvine.zond.parsers.XmlParser;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Настройки логирования: logback*.xml и log4j2*.xml
 */
@UtilityClass
class LogConfigs {
    private static final String TEST = "test";

    /**
     * @return корневой элемент настроек, которые попадают в рабочую среду. Настройки для тестов
     * (logback-test.xml, все из src/test) не проверяются: подробный вывод там уместен
     */
    Optional<XmlElement> production(TextFile file) {
        if (!TextFiles.isLogConfig(file) || isForTests(file)) {
            return Optional.empty();
        }
        return XmlParser.parse(file.lines());
    }

    boolean isLogback(TextFile file) {
        return file.type() == TextFileType.LOGBACK;
    }

    private boolean isForTests(TextFile file) {
        if (file.name().toLowerCase(Locale.ROOT).contains(TEST)) {
            return true;
        }
        for (Path part : file.path()) {
            if (TEST.equals(part.toString())) {
                return true;
            }
        }
        return false;
    }
}
