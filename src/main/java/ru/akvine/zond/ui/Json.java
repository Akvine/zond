package ru.akvine.zond.ui;

import lombok.experimental.UtilityClass;

import java.util.Collection;

/**
 * Запись ответов страницы в JSON. Ответы маленькие и плоские, поэтому отдельная библиотека не нужна
 */
@UtilityClass
class Json {
    private static final int FIRST_PRINTABLE = 0x20;

    /**
     * @return строка в кавычках с экранированием либо null
     */
    String text(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder json = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char symbol = value.charAt(i);
            switch (symbol) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                // Угловая скобка экранируется, чтобы ответ нельзя было принять за разметку
                case '<' -> json.append("\\u003c");
                default -> {
                    if (symbol < FIRST_PRINTABLE) {
                        json.append(String.format("\\u%04x", (int) symbol));
                    } else {
                        json.append(symbol);
                    }
                }
            }
        }
        return json.append('"').toString();
    }

    /**
     * @param items элементы, уже записанные в JSON
     */
    String array(Collection<String> items) {
        return "[" + String.join(",", items) + "]";
    }

    /**
     * @param fields имя и значение по очереди; значения уже записаны в JSON
     */
    String object(String... fields) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < fields.length; i += 2) {
            if (i > 0) {
                json.append(',');
            }
            json.append(text(fields[i])).append(':').append(fields[i + 1]);
        }
        return json.append('}').toString();
    }
}
