package ru.akvine.zond.cli;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Главное меню: открывается, когда путь для сканирования не передан аргументом
 */
@Component
@RequiredArgsConstructor
public class MainMenu {
    private static final String SCAN_FOLDER_TITLE = "Выберите папку для сканирования";
    private static final String NOT_CHOSEN = "папка не выбрана";

    private final ConsoleMenu menu;
    private final FolderPicker folderPicker;
    private final SettingsMenu settingsMenu;
    private final RulesMenu rulesMenu;
    private final ScanExecutor scanExecutor;

    /**
     * @return код завершения последнего сканирования; 0, если сканирование не запускалось
     */
    public int open(SessionSettings settings) {
        int exitCode = ScanExecutor.EXIT_OK;
        try {
            // Начинаем с папки, из которой запущено приложение: чаще всего проект лежит в ней или рядом
            Path target = folderPicker.pick(Path.of(""), SCAN_FOLDER_TITLE, false).orElse(null);

            while (true) {
                int choice = menu.choose("Главное меню", List.of(
                        "Сканировать (" + (target == null ? NOT_CHOSEN : target) + ")",
                        "Выбрать другую папку",
                        "Настройки",
                        "Список правил",
                        "Выход"));
                switch (choice) {
                    case 1 -> {
                        if (target == null) {
                            target = chooseTarget(null);
                        }
                        if (target != null) {
                            exitCode = scanExecutor.scan(target, settings);
                        }
                    }
                    case 2 -> target = chooseTarget(target);
                    case 3 -> settingsMenu.open(settings);
                    case 4 -> rulesMenu.open(settings);
                    default -> {
                        return exitCode;
                    }
                }
            }
        } catch (InputClosedException exception) {
            // Ввод закончился (Ctrl+D, перенаправленный файл дочитан) - выходим так же, как через пункт "Выход"
            return exitCode;
        }
    }

    // Отказ от выбора оставляет прежнюю папку
    private Path chooseTarget(Path current) {
        Path start = current == null ? Path.of("") : current;
        Optional<Path> chosen = folderPicker.pick(start, SCAN_FOLDER_TITLE, false);
        return chosen.orElse(current);
    }
}
