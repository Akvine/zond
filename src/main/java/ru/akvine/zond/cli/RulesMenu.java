package ru.akvine.zond.cli;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ReportFormat;
import ru.akvine.zond.models.RuleInfo;
import ru.akvine.zond.printers.RuleListFormatter;
import ru.akvine.zond.printers.RuleListWriter;
import ru.akvine.zond.services.RuleCatalog;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Пункт меню "Список правил": показывает правила с кодами и описаниями либо выгружает их в файл
 */
@Component
@RequiredArgsConstructor
public class RulesMenu {
    private static final String FILE_NAME = "zond-rules";

    private final ConsoleMenu menu;
    private final RuleCatalog ruleCatalog;
    private final RuleListFormatter formatter;
    private final RuleListWriter writer;

    /**
     * @throws InputClosedException если ввод закончился
     */
    public void open(SessionSettings settings) {
        while (true) {
            int choice = menu.choose("Список правил", List.of(
                    "Показать в консоли",
                    "Выгрузить в текстовый файл (.txt)",
                    "Выгрузить в Excel (.xlsx)",
                    "Назад"));
            List<RuleInfo> rules = ruleCatalog.describe(settings.toScanOptions());
            switch (choice) {
                case 1 -> {
                    System.out.println();
                    System.out.print(formatter.format(rules));
                }
                case 2 -> export(settings, ReportFormat.TXT, rules);
                case 3 -> export(settings, ReportFormat.XLSX, rules);
                default -> {
                    return;
                }
            }
        }
    }

    // Список кладется рядом с отчетами, а если папка для них не задана - в текущую
    private void export(SessionSettings settings, ReportFormat format, List<RuleInfo> rules) {
        String fileName = FILE_NAME + format.getExtension();
        Path directory = settings.getReportDirectory();
        Path file = directory == null ? Path.of(fileName) : directory.resolve(fileName);
        try {
            writer.write(file, rules);
            System.out.println("Список правил записан: " + file.toAbsolutePath().normalize()
                    + " (правил: " + rules.size() + ")");
        } catch (UncheckedIOException exception) {
            System.out.println("Ошибка: " + exception.getMessage());
        }
    }
}
