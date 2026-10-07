package ru.akvine.zond.models;

/**
 * Сколько работало одно правило
 *
 * @param nanos время работы в наносекундах
 */
public record RuleTiming(String code, String name, long nanos) {
}
