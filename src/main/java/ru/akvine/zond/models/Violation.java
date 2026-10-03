package ru.akvine.zond.models;

import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;

import java.nio.file.Path;

public record Violation(ErrorLevel errorLevel, ErrorType errorType, String ruleCode, String ruleName, Path file, int line, String message) {
}
