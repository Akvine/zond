package ru.akvine.zond.enums;

import java.util.Locale;

public enum ErrorLevel {
    CRITICAL,
    MAJOR,
    MINOR,
    INFO;

    // Уровня BLOCKER больше нет: в настройках, написанных раньше, он читается как самый строгий из оставшихся
    private static final String REMOVED_BLOCKER = "BLOCKER";

    /**
     * @throws IllegalArgumentException если уровня с таким именем нет
     */
    public static ErrorLevel parse(String name) {
        String level = name.trim().toUpperCase(Locale.ROOT);
        return REMOVED_BLOCKER.equals(level) ? CRITICAL : valueOf(level);
    }
}
