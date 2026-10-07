package ru.akvine.zond.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Каким цветом отмечать правило в отчете по времени. Объявлены от самой тяжелой к самой легкой.
 */
@AllArgsConstructor
@Getter
public enum TimingZone {
    RED("Красный"),
    YELLOW("Желтый"),
    GREEN("Зеленый"),
    /** Доля меньше порога зеленого: строка остается без заливки */
    NONE("Без цвета");

    private final String title;
}
