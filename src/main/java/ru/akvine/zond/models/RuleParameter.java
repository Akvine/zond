package ru.akvine.zond.models;

/**
 * Настраиваемый порог правила
 *
 * @param name         имя в настройках: zond.rule.jr-193.max-complexity
 * @param defaultValue значение, если в настройках параметр не задан
 * @param description  что означает параметр - для списка правил
 */
public record RuleParameter(String name, int defaultValue, String description) {
}
