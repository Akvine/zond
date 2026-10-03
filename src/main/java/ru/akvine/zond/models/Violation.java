package ru.akvine.zond.models;

import ru.akvine.zond.enums.ErrorLevel;

import java.nio.file.Path;

public record Violation(ErrorLevel errorLevel, String ruleCode, String ruleName, Path file, int line, String message) {
}
