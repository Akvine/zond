package ru.akvine.zond.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * В чем показывать время работы правил и всей проверки
 */
@AllArgsConstructor
@Getter
public enum DurationUnit {
    MILLISECONDS("ms", "мс", "миллисекунды", "0"),
    SECONDS("s", "с", "секунды", "0.000");

    private static final long NANOS_IN_MILLI = 1_000_000L;
    private static final double NANOS_IN_SECOND = 1_000_000_000.0;
    private static final String SECONDS_FORMAT = "%.3f";

    /** Как единица задается в настройках */
    private final String code;
    /** Как единица подписывается рядом с числом */
    private final String sign;
    private final String title;
    /** Числовой формат ячейки Excel для времени в этой единице */
    private final String cellFormat;

    /**
     * @param value код (ms, s) либо имя единицы; пустая строка - миллисекунды
     * @throws IllegalArgumentException если такой единицы нет
     */
    public static DurationUnit parse(String value) {
        if (value == null || value.isBlank()) {
            return MILLISECONDS;
        }
        String wanted = value.trim();
        return Arrays.stream(values())
                .filter(unit -> unit.code.equalsIgnoreCase(wanted) || unit.name().equalsIgnoreCase(wanted))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Неизвестная единица времени '" + wanted
                        + "'. Допустимые значения: "
                        + Arrays.stream(values()).map(DurationUnit::getCode).collect(Collectors.joining(", "))));
    }

    /**
     * @return время в этой единице числом: целые миллисекунды либо секунды с долями
     */
    public double value(long nanos) {
        return this == MILLISECONDS ? nanos / NANOS_IN_MILLI : nanos / NANOS_IN_SECOND;
    }

    /**
     * @return время в этой единице вместе с подписью: "1234 мс" либо "1.234 с"
     */
    public String format(long nanos) {
        // Секунды идут с долями: большинство правил отрабатывает быстрее секунды
        String number = this == MILLISECONDS
                ? String.valueOf(nanos / NANOS_IN_MILLI)
                : String.format(Locale.ROOT, SECONDS_FORMAT, nanos / NANOS_IN_SECOND);
        return number + " " + sign;
    }
}
