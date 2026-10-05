package ru.akvine.zond.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Насколько анализатор уверен в находке. Уровень (ErrorLevel) говорит, насколько плоха проблема, если она есть;
 * уверенность - есть ли она на самом деле. Объявлены от самой высокой к самой низкой.
 */
@AllArgsConstructor
@Getter
public enum Confidence {
    /** Установлено по коду: путь данных прослежен целиком, значение вычислено, факт виден в самом тексте */
    CONFIRMED("подтверждено"),
    /** Похоже на проблему, но часть условий по коду проверить нельзя */
    PROBABLE("вероятно"),
    /** Совпадение по имени или шаблону: стоит посмотреть, но ложное срабатывание вполне возможно */
    SUSPICION("подозрение");

    private final String title;

    /**
     * @param name имя уровня уверенности; пустая строка - без порога
     * @throws IllegalArgumentException если такого уровня нет
     */
    public static Confidence parse(String name) {
        if (name == null || name.isBlank()) {
            return SUSPICION;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Неизвестная уверенность '" + name.trim() + "'. Допустимые значения: "
                    + Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", ")), exception);
        }
    }

    /**
     * @return true, если эта уверенность не ниже порога
     */
    public boolean isAtLeast(Confidence threshold) {
        return ordinal() <= threshold.ordinal();
    }
}
