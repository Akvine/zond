package ru.akvine.zond.cli;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;

@Component
public class ConsoleInput {
    // Запасной вариант, когда System.console() недоступна (запуск из IDE, перенаправленный ввод)
    private final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

    /**
     * @return введенная строка или null, если ввод закончился
     */
    public String readLine(String prompt) {
        Console console = System.console();
        if (console != null) {
            return console.readLine("%s", prompt);
        }

        System.out.print(prompt);
        System.out.flush();
        try {
            return reader.readLine();
        } catch (IOException exception) {
            throw new UncheckedIOException("Не удалось прочитать ввод", exception);
        }
    }
}
