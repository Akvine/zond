package ru.akvine.zond.cli;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Выбор папки в консоли: показывает вложенные папки, позволяет ходить по ним и вводить путь вручную
 */
@Component
@RequiredArgsConstructor
public class FolderPicker {
    private static final String HIDDEN_PREFIX = ".";
    private static final String JAVA_EXTENSION = ".java";
    private static final String PARENT = ".. (на уровень выше)";

    // Больше папок на экране не разобрать - остальные выбираются вводом пути
    private static final int MAX_LISTED = 40;

    private final ConsoleInput input;

    /**
     * @param start    с какой папки начать
     * @param allowNew можно ли выбрать папку, которой еще нет (она будет создана позже)
     * @return выбранная папка либо пусто, если пользователь отказался от выбора
     * @throws InputClosedException если ввод закончился
     */
    public Optional<Path> pick(Path start, String title, boolean allowNew) {
        Path current = start.toAbsolutePath().normalize();
        while (!Files.isDirectory(current) && current.getParent() != null) {
            current = current.getParent();
        }

        while (true) {
            List<Path> entries = listEntries(current);
            print(title, current, entries);

            String answer = unquote(input.ask("Номер, путь к папке или пустая строка для отмены: "));
            if (answer.isEmpty()) {
                return Optional.empty();
            }

            Optional<Integer> number = parseNumber(answer);
            if (number.isPresent()) {
                if (number.get() == 0) {
                    return Optional.of(current);
                }
                if (number.get() >= 1 && number.get() <= entries.size()) {
                    current = entries.get(number.get() - 1);
                } else {
                    System.out.println("Нет пункта с номером " + number.get());
                }
                continue;
            }

            // Введенный путь выбирается сразу: относительный отсчитывается от показанной папки
            Path typed = current.resolve(answer).normalize();
            if (Files.isDirectory(typed) || isJavaFile(typed) || allowNew) {
                return Optional.of(typed);
            }
            System.out.println("Папка не найдена: " + typed);
        }
    }

    private void print(String title, Path current, List<Path> entries) {
        System.out.println();
        System.out.println(title);
        System.out.println("Папка: " + current);
        System.out.println("  0. Выбрать эту папку");

        int listed = Math.min(entries.size(), MAX_LISTED);
        for (int index = 0; index < listed; index++) {
            Path entry = entries.get(index);
            boolean isParent = entry.equals(current.getParent());
            System.out.println("  " + (index + 1) + ". " + (isParent ? PARENT : entry.getFileName()));
        }
        if (entries.size() > listed) {
            System.out.println("  ... и еще " + (entries.size() - listed) + " - введите номер или путь");
        }
    }

    // Сначала переход наверх, затем вложенные папки по алфавиту; скрытые и служебные (.git, .idea) не показываются
    private List<Path> listEntries(Path directory) {
        List<Path> entries = new ArrayList<>();
        if (directory.getParent() != null) {
            entries.add(directory.getParent());
        }
        try (Stream<Path> children = Files.list(directory)) {
            children.filter(Files::isDirectory)
                    .filter(child -> !child.getFileName().toString().startsWith(HIDDEN_PREFIX))
                    .sorted()
                    .forEach(entries::add);
        } catch (IOException | RuntimeException exception) {
            System.out.println("Не удалось прочитать содержимое папки: " + exception.getMessage());
        }
        return entries;
    }

    private Optional<Integer> parseNumber(String text) {
        try {
            return Optional.of(Integer.parseInt(text));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    // Сканировать можно и отдельный файл
    private boolean isJavaFile(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(JAVA_EXTENSION);
    }

    // Проводник Windows при "Копировать как путь" оборачивает путь в кавычки
    private String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }
}
