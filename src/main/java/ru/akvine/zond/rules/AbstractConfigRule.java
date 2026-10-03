package ru.akvine.zond.rules;

import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.Violation;

/**
 * Общая часть правил для файлов настроек
 */
public abstract class AbstractConfigRule extends AbstractRule implements ConfigRule {
    private static final int FIRST_LINE = 1;

    protected Violation violation(ConfigFile configFile, int line, String message) {
        return new Violation(errorLevel(), errorType(), code(), name(), configFile.path(), line, message);
    }

    /**
     * Нарушение, которое относится к файлу целиком, например отсутствие нужного свойства
     */
    protected Violation violation(ConfigFile configFile, String message) {
        return violation(configFile, FIRST_LINE, message);
    }
}
