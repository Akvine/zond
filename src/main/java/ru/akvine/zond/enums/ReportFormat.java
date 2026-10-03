package ru.akvine.zond.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum ReportFormat {
    CONSOLE(null, "Консоль"),
    TXT(".txt", "Текстовый файл (.txt)"),
    XLSX(".xlsx", "Excel (.xlsx)");

    /**
     * Расширение файла отчета; у вывода в консоль файла нет
     */
    private final String extension;
    private final String description;
}
