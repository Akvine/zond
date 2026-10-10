package ru.akvine.zond.cli;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.DurationUnit;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.FileKind;
import ru.akvine.zond.enums.ReportFormat;
import ru.akvine.zond.loaders.GitChangedFiles;
import ru.akvine.zond.models.PathExclusions;
import ru.akvine.zond.services.RuleCatalog;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Пункт меню "Настройки": каждое изменение сразу сохраняется в файл настроек
 */
@Component
@RequiredArgsConstructor
public class SettingsMenu {
    private static final String RULES_SEPARATOR = "[,;\\s]+";
    private static final String CURRENT_DIRECTORY = "текущая папка";
    private static final String BACK = "Назад";
    private static final String CLEAR = "-";
    private static final String ENABLED = "проверяются";
    private static final String DISABLED = "не проверяются";

    private final ConsoleMenu menu;
    private final ConsoleInput input;
    private final FolderPicker folderPicker;
    private final RuleCatalog ruleCatalog;
    private final SettingsStore store;

    /**
     * @throws InputClosedException если ввод закончился
     */
    public void open(SessionSettings settings) {
        while (true) {
            int choice = menu.choose("Настройки (файл: " + store.file() + ")", List.of(
                    "Папка для отчета: " + describeDirectory(settings),
                    "Формат отчета: " + settings.reportFormat().getDescription(),
                    "Сканирование тестов: " + (settings.isSkipTests() ? "отключено" : "включено"),
                    "Минимальный уровень: " + settings.getMinLevel(),
                    "Минимальная уверенность: " + describe(settings.getMinConfidence()),
                    "Отключенные правила: " + describeDisabled(settings),
                    "Исключенные пути: " + describeExclusions(settings),
                    "Файлы помимо Java: " + describeKinds(settings),
                    "Единица времени: " + settings.getTimeUnit().getTitle(),
                    "Потоки сканирования: " + describeThreads(settings.getThreads()),
                    "Только измененные файлы: " + describeChanged(settings),
                    BACK));
            switch (choice) {
                case 1 -> chooseReportDirectory(settings);
                case 2 -> chooseReportFormat(settings);
                case 3 -> toggleTests(settings);
                case 4 -> chooseMinLevel(settings);
                case 5 -> chooseMinConfidence(settings);
                case 6 -> editDisabledRules(settings);
                case 7 -> editExclusions(settings);
                case 8 -> editKinds(settings);
                case 9 -> chooseTimeUnit(settings);
                case 10 -> chooseThreads(settings);
                case 11 -> editChangedOnly(settings);
                default -> {
                    return;
                }
            }
        }
    }

    private void chooseReportDirectory(SessionSettings settings) {
        Path start = settings.getReportDirectory() == null ? Path.of("") : settings.getReportDirectory();
        Optional<Path> directory = folderPicker.pick(start, "Выберите папку для отчета", true);
        if (directory.isEmpty()) {
            return;
        }

        settings.setReportDirectory(directory.get());
        // Папка нужна только файловому отчету - иначе выбор ни на что бы не повлиял
        if (settings.reportFormat() == ReportFormat.CONSOLE) {
            settings.setReportFormat(ReportFormat.TXT);
        }
        System.out.println("Отчет будет записан в " + settings.reportFile().toAbsolutePath().normalize());
        save(settings);
    }

    private void chooseReportFormat(SessionSettings settings) {
        List<ReportFormat> formats = List.of(ReportFormat.values());
        int choice = menu.choose("Формат отчета", formats.stream().map(ReportFormat::getDescription).toList());
        settings.setReportFormat(formats.get(choice - 1));
        save(settings);
    }

    private void toggleTests(SessionSettings settings) {
        settings.setSkipTests(!settings.isSkipTests());
        System.out.println(settings.isSkipTests()
                ? "Каталоги test больше не сканируются"
                : "Каталоги test сканируются вместе с остальным кодом");
        save(settings);
    }

    private void chooseMinLevel(SessionSettings settings) {
        List<ErrorLevel> levels = List.of(ErrorLevel.values());
        int choice = menu.choose(
                "Минимальный уровень: в отчет попадают проблемы этого уровня и строже",
                levels.stream().map(Enum::name).toList());
        settings.setMinLevel(levels.get(choice - 1));
        save(settings);
    }

    private void chooseMinConfidence(SessionSettings settings) {
        List<Confidence> values = List.of(Confidence.values());
        int choice = menu.choose(
                "Минимальная уверенность: в отчет попадают находки с этой уверенностью и выше",
                values.stream().map(this::describe).toList());
        settings.setMinConfidence(values.get(choice - 1));
        save(settings);
    }

    // Число спрашивается, пока не введут допустимое: с ошибкой во вводе настройка не меняется
    private void chooseThreads(SessionSettings settings) {
        System.out.println("Сколько потоков использовать при сканировании: от 1 до " + SessionSettings.MAX_THREADS
                + "; 0 - по числу ядер процессора (на этой машине их " + Runtime.getRuntime().availableProcessors() + ")");
        while (true) {
            String answer = input.ask("Число потоков (пустая строка - отмена): ");
            if (answer.isEmpty()) {
                return;
            }
            try {
                settings.setThreads(SessionSettings.parseThreads(answer));
                break;
            } catch (IllegalArgumentException exception) {
                System.out.println(exception.getMessage());
            }
        }
        System.out.println("Потоки сканирования: " + describeThreads(settings.getThreads()));
        save(settings);
    }

    private String describeThreads(int threads) {
        return threads == 0
                ? "по числу ядер процессора (" + Runtime.getRuntime().availableProcessors() + ")"
                : String.valueOf(threads);
    }

    private void editChangedOnly(SessionSettings settings) {
        while (true) {
            int choice = menu.choose("Только измененные файлы: в репозитории git изменения берутся у git,"
                    + " в остальных папках - по хешам файлов с прошлой проверки", List.of(
                    "Показывать находки: " + (settings.isChangedOnly() ? "только в измененных файлах" : "во всех файлах"),
                    "С чем сравнивать в репозитории git: " + describeSince(settings),
                    BACK));
            switch (choice) {
                case 1 -> {
                    settings.setChangedOnly(!settings.isChangedOnly());
                    save(settings);
                }
                case 2 -> chooseChangedSince(settings);
                default -> {
                    return;
                }
            }
        }
    }

    // Есть ли такая ветка, покажет только git в папке проекта - она выбирается позже. Здесь проверяется,
    // что введенное вообще похоже на имя ветки, тега или коммита
    private void chooseChangedSince(SessionSettings settings) {
        System.out.println("Ветка, тег или коммит, с которым сравнивать рабочую копию: main, origin/main, v1.2.0, HEAD~3."
                + " Без него сравнение идет с HEAD - изменено то, что еще не закоммичено."
                + " Вне репозитория git не используется: там сравнение всегда с прошлой проверкой");
        while (true) {
            String answer = input.ask("С чем сравнивать (пустая строка - отмена, '-' - с HEAD): ");
            if (answer.isEmpty()) {
                return;
            }
            try {
                settings.setChangedSince(CLEAR.equals(answer) ? "" : GitChangedFiles.validate(answer));
                break;
            } catch (IllegalArgumentException exception) {
                System.out.println(exception.getMessage());
            }
        }
        save(settings);
    }

    private String describeChanged(SessionSettings settings) {
        return settings.isChangedOnly()
                ? "да, в git сравнение с " + describeSince(settings) + ", без git - с прошлой проверкой"
                : "нет, проверяется все";
    }

    private String describeSince(SessionSettings settings) {
        return settings.getChangedSince().isEmpty()
                ? GitChangedFiles.HEAD + " (незакоммиченные изменения)"
                : settings.getChangedSince();
    }

    private void chooseTimeUnit(SessionSettings settings) {
        List<DurationUnit> units = List.of(DurationUnit.values());
        int choice = menu.choose(
                "Единица времени: в ней показывается время работы каждого правила и всей проверки",
                units.stream().map(DurationUnit::getTitle).toList());
        settings.setTimeUnit(units.get(choice - 1));
        save(settings);
    }

    private String describe(Confidence confidence) {
        return confidence.name() + " (" + confidence.getTitle() + ")";
    }

    private void editDisabledRules(SessionSettings settings) {
        while (true) {
            int choice = menu.choose("Отключенные правила: " + describeDisabled(settings), List.of(
                    "Отключить правила",
                    "Включить правила обратно",
                    "Включить все правила",
                    BACK));
            switch (choice) {
                case 1 -> disableRules(settings);
                case 2 -> enableRules(settings);
                case 3 -> {
                    settings.getDisabledRules().clear();
                    save(settings);
                }
                default -> {
                    return;
                }
            }
        }
    }

    private void editExclusions(SessionSettings settings) {
        System.out.println("Шаблоны через запятую: имя каталога (generated), путь от корня сканирования"
                + " (src/main/java/legacy) либо шаблон (*Dto.java, **/generated/**)");
        String answer = input.ask("Исключенные пути (пустая строка - отмена, '-' - очистить): ");
        if (answer.isEmpty()) {
            return;
        }
        settings.setExclusions(CLEAR.equals(answer) ? PathExclusions.none() : PathExclusions.parse(answer));
        save(settings);
    }

    // Выбор вида файлов переключает его проверку
    private void editKinds(SessionSettings settings) {
        List<FileKind> kinds = List.of(FileKind.values());
        while (true) {
            List<String> items = new ArrayList<>();
            for (FileKind kind : kinds) {
                items.add(kind.getDescription() + ": " + (settings.isScanned(kind) ? ENABLED : DISABLED));
            }
            items.add(BACK);
            int choice = menu.choose("Файлы помимо Java: выберите вид, чтобы включить или отключить его проверку", items);
            if (choice > kinds.size()) {
                return;
            }
            FileKind kind = kinds.get(choice - 1);
            settings.setScanned(kind, !settings.isScanned(kind));
            save(settings);
        }
    }

    private String describeKinds(SessionSettings settings) {
        List<String> skipped = Arrays.stream(FileKind.values())
                .filter(kind -> !settings.isScanned(kind))
                .map(FileKind::getKey)
                .toList();
        return skipped.isEmpty() ? "проверяются все" : "не проверяются " + String.join(", ", skipped);
    }

    private String describeExclusions(SessionSettings settings) {
        List<String> patterns = settings.getExclusions().patterns();
        return patterns.isEmpty() ? "нет" : String.join(", ", patterns);
    }

    private void disableRules(SessionSettings settings) {
        boolean changed = false;
        for (String rule : askRules()) {
            Optional<String> code = ruleCatalog.findCode(rule);
            if (code.isEmpty()) {
                System.out.println("Правило не найдено: " + rule);
            } else if (isDisabled(settings, code.get())) {
                System.out.println("Правило уже отключено: " + rule);
            } else {
                settings.getDisabledRules().add(code.get());
                changed = true;
            }
        }
        if (changed) {
            save(settings);
        }
    }

    private void enableRules(SessionSettings settings) {
        if (settings.getDisabledRules().isEmpty()) {
            System.out.println("Отключенных правил нет");
            return;
        }

        boolean changed = false;
        for (String rule : askRules()) {
            // В файле настроек правило могло быть записано именем - сравниваем по коду
            String code = ruleCatalog.findCode(rule).orElse(rule);
            boolean removed = settings.getDisabledRules()
                    .removeIf(disabled -> ruleCatalog.findCode(disabled).orElse(disabled).equalsIgnoreCase(code));
            if (!removed) {
                System.out.println("Правило не было отключено: " + rule);
            }
            changed |= removed;
        }
        if (changed) {
            save(settings);
        }
    }

    private List<String> askRules() {
        String answer = input.ask("Коды или имена правил через запятую (пустая строка - отмена): ");
        return Arrays.stream(answer.split(RULES_SEPARATOR)).filter(rule -> !rule.isBlank()).toList();
    }

    private boolean isDisabled(SessionSettings settings, String code) {
        return settings.getDisabledRules().stream()
                .anyMatch(disabled -> ruleCatalog.findCode(disabled).orElse(disabled).equalsIgnoreCase(code));
    }

    private String describeDirectory(SessionSettings settings) {
        if (settings.reportFormat() == ReportFormat.CONSOLE) {
            return "не используется, отчет выводится в консоль";
        }
        Path directory = settings.getReportDirectory();
        return directory == null ? CURRENT_DIRECTORY : directory.toAbsolutePath().normalize().toString();
    }

    private String describeDisabled(SessionSettings settings) {
        Set<String> disabled = settings.getDisabledRules();
        return disabled.isEmpty() ? "нет" : settings.disabledRulesAsText();
    }

    // Не удалось сохранить - настройки все равно действуют до конца сеанса
    private void save(SessionSettings settings) {
        try {
            store.save(settings);
            System.out.println("Настройки сохранены");
        } catch (UncheckedIOException exception) {
            System.out.println(exception.getMessage() + ". Изменение действует до выхода из приложения");
        }
    }
}
