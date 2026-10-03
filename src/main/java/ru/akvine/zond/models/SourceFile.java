package ru.akvine.zond.models;

import com.github.javaparser.ast.CompilationUnit;

import java.nio.file.Path;

/**
 * Разобранный исходный файл: путь + AST
 */
public record SourceFile(Path path, CompilationUnit unit) {
}
