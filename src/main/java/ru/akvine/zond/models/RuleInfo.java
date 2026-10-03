package ru.akvine.zond.models;

import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;

/**
 * Строка справочника правил
 *
 * @param active false, если правило выключено в коде либо отключено текущими настройками
 */
public record RuleInfo(
        String code, String name, ErrorLevel level, ErrorType type, String description, boolean active) {
}
