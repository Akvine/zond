package ru.akvine.zond.models;

/**
 * Свойство из файла настроек
 *
 * @param key  полный ключ: spring.jpa.hibernate.ddl-auto
 * @param line строка файла, на которой свойство задано
 */
public record ConfigProperty(String key, String value, int line) {
}
