package ru.akvine.zond.config;

import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Версия приложения. Задается один раз - в build.gradle; сюда попадает при сборке через файл
 * META-INF/build-info.properties, который создает Spring Boot.
 */
@UtilityClass
public class ZondVersion {
    private static final String BUILD_INFO = "META-INF/build-info.properties";
    private static final String VERSION_KEY = "build.version";
    private static final String TOOL_NAME = "Zond";
    // Запуск из среды разработки без сборки Gradle: файла с версией еще нет
    private static final String UNKNOWN = "dev";

    private static final String CURRENT = read();

    /**
     * @return версия как в build.gradle либо dev, если приложение запущено не из сборки
     */
    public String current() {
        return CURRENT;
    }

    /**
     * @return имя приложения вместе с версией: "Zond 1.2.0"
     */
    public String title() {
        return TOOL_NAME + " " + CURRENT;
    }

    private String read() {
        try (InputStream input = ZondVersion.class.getClassLoader().getResourceAsStream(BUILD_INFO)) {
            if (input == null) {
                return UNKNOWN;
            }
            Properties properties = new Properties();
            properties.load(input);
            String version = properties.getProperty(VERSION_KEY, "").trim();
            return version.isEmpty() ? UNKNOWN : version;
        } catch (IOException exception) {
            return UNKNOWN;
        }
    }
}
