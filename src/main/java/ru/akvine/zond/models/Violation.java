package ru.akvine.zond.models;

import java.nio.file.Path;

public record Violation(String ruleCode, String ruleName, Path file, int line, String message) {
}
