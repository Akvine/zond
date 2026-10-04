package ru.akvine.zond.models;

import java.nio.file.Path;
import java.util.List;

/**
 * Все, что загружено для проверки: нужно правилам, которые сверяют разные виды файлов между собой
 *
 * @param root        что сканировали
 * @param sources     разобранные Java-файлы
 * @param configFiles файлы настроек Spring: application*.properties / yml
 * @param textFiles   остальные проверяемые файлы: SQL, сборка, Dockerfile, сообщения
 */
public record ScanContext(Path root, List<SourceFile> sources, List<ConfigFile> configFiles, List<TextFile> textFiles) {
}
