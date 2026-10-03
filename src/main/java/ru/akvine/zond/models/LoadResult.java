package ru.akvine.zond.models;

import java.nio.file.Path;
import java.util.List;

/**
 * @param sources     успешно разобранные файлы
 * @param failedFiles файлы, которые не удалось разобрать
 */
public record LoadResult(List<SourceFile> sources, List<Path> failedFiles) {
}
