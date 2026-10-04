package ru.akvine.zond.rules;

import ru.akvine.zond.models.Violation;

import java.nio.file.Path;

/**
 * Общая часть правил, которые проверяют не Java-код: сборка нарушения по файлу и номеру строки
 */
public abstract class AbstractContextRule extends AbstractRule implements ContextRule {
    private static final int FIRST_LINE = 1;

    protected Violation violation(Path file, int line, String message) {
        return new Violation(errorLevel(), errorType(), code(), name(), file, line, message);
    }

    /**
     * Нарушение, которое относится к файлу целиком
     */
    protected Violation violation(Path file, String message) {
        return violation(file, FIRST_LINE, message);
    }
}
