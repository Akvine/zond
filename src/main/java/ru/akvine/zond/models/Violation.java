package ru.akvine.zond.models;

import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;

import java.nio.file.Path;

/**
 * Находка правила
 *
 * @param confidence насколько анализатор в ней уверен; null - правило не уточнило, действует
 *                   уверенность самого правила
 */
public record Violation(
        ErrorLevel errorLevel, ErrorType errorType, String ruleCode, String ruleName, Path file, int line,
        String message, Confidence confidence) {

    public Violation(
            ErrorLevel errorLevel, ErrorType errorType, String ruleCode, String ruleName, Path file, int line,
            String message) {
        this(errorLevel, errorType, ruleCode, ruleName, file, line, message, null);
    }

    public Violation withConfidence(Confidence value) {
        return new Violation(errorLevel, errorType, ruleCode, ruleName, file, line, message, value);
    }

    public Violation withLevel(ErrorLevel level) {
        return new Violation(level, errorType, ruleCode, ruleName, file, line, message, confidence);
    }

    /**
     * @return уверенность для вывода в отчет: если ее никто не задал, находка считается вероятной
     */
    public Confidence confidenceOrDefault() {
        return confidence == null ? Confidence.PROBABLE : confidence;
    }
}
