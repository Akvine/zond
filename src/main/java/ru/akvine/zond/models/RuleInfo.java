package ru.akvine.zond.models;

import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;

import java.util.List;

/**
 * Строка справочника правил
 *
 * @param level    действующий уровень: из настроек, если он там переопределен
 * @param active   false, если правило выключено в коде либо отключено текущими настройками
 * @param settings настройки правила в виде строк для app.properties: пороги с текущими значениями
 *                 и уровень, если он переопределен
 */
public record RuleInfo(
        String code,
        String name,
        ErrorLevel level,
        ErrorType type,
        String description,
        boolean active,
        List<String> settings) {
}
