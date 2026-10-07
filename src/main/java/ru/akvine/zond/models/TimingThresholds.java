package ru.akvine.zond.models;

import ru.akvine.zond.enums.TimingZone;

/**
 * Пороги цветов в отчете по времени: доля правила в общем времени всех правил, в процентах.
 * Правило получает самый тяжелый цвет, порога которого достигло.
 *
 * @param red    с какой доли строка красная
 * @param yellow с какой доли строка желтая
 * @param green  с какой доли строка зеленая; 0 - зеленые все, кто не дотянул до желтого
 */
public record TimingThresholds(double red, double yellow, double green) {
    private static final double DEFAULT_RED = 10;
    private static final double DEFAULT_YELLOW = 3;
    private static final double DEFAULT_GREEN = 0;
    private static final double MAX_PERCENT = 100;

    public static TimingThresholds defaults() {
        return new TimingThresholds(DEFAULT_RED, DEFAULT_YELLOW, DEFAULT_GREEN);
    }

    /**
     * @param red    порог красного как задан в настройках; пустая строка - значение по умолчанию
     * @param yellow порог желтого
     * @param green  порог зеленого
     * @throws IllegalArgumentException если порог - не число от 0 до 100 либо пороги идут не по убыванию
     */
    public static TimingThresholds parse(String red, String yellow, String green) {
        TimingThresholds thresholds = new TimingThresholds(
                parse("красного", red, DEFAULT_RED),
                parse("желтого", yellow, DEFAULT_YELLOW),
                parse("зеленого", green, DEFAULT_GREEN));
        // Иначе более легкий цвет никогда бы не выпал: его перекрывал бы более тяжелый
        if (thresholds.red < thresholds.yellow || thresholds.yellow < thresholds.green) {
            throw new IllegalArgumentException("Пороги цветов в отчете по времени должны идти по убыванию:"
                    + " красный " + thresholds.red + ", желтый " + thresholds.yellow + ", зеленый " + thresholds.green);
        }
        return thresholds;
    }

    public TimingZone zoneOf(double percent) {
        if (percent >= red) {
            return TimingZone.RED;
        }
        if (percent >= yellow) {
            return TimingZone.YELLOW;
        }
        return percent >= green ? TimingZone.GREEN : TimingZone.NONE;
    }

    public double of(TimingZone zone) {
        return switch (zone) {
            case RED -> red;
            case YELLOW -> yellow;
            default -> green;
        };
    }

    private static double parse(String color, String value, double byDefault) {
        if (value == null || value.isBlank()) {
            return byDefault;
        }
        try {
            double percent = Double.parseDouble(value.trim().replace(',', '.'));
            if (percent >= 0 && percent <= MAX_PERCENT) {
                return percent;
            }
        } catch (NumberFormatException exception) {
            // ниже сообщаем о неверном значении так же, как о числе вне диапазона
        }
        throw new IllegalArgumentException("Порог " + color + " цвета в отчете по времени должен быть числом от 0 до 100,"
                + " а задано '" + value.trim() + "'");
    }
}
