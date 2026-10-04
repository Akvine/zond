package ru.akvine.zond.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;

@AllArgsConstructor
@Getter
public enum ReportFormat {
    CONSOLE(null, "Консоль"),
    TXT(".txt", "Текстовый файл (.txt)"),
    XLSX(".xlsx", "Excel (.xlsx)"),
    HTML(".html", "Веб-страница (.html)"),
    SARIF(".sarif", "SARIF для GitHub, GitLab и IDE (.sarif)");

    // GitHub принимает и такое имя: report.sarif.json
    private static final String SARIF_JSON = ".sarif.json";

    /**
     * Расширение файла отчета; у вывода в консоль файла нет
     */
    private final String extension;
    private final String description;

    /**
     * @return формат по расширению файла; файл с незнакомым расширением - текстовый
     */
    public static ReportFormat of(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(SARIF_JSON)) {
            return SARIF;
        }
        return Arrays.stream(values())
                .filter(format -> format.extension != null && name.endsWith(format.extension))
                .findFirst()
                .orElse(TXT);
    }
}
