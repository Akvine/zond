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
 * @param folderFiles то же, что textFiles, но по всей проверяемой папке. У независимой части проекта этот список
 *                    шире ее собственных файлов: по нему видно, что зависимость - модуль, который лежит в той же
 *                    папке, и где искать родительский pom.xml
 */
public record ScanContext(Path root, List<SourceFile> sources, List<ConfigFile> configFiles, List<TextFile> textFiles,
                          List<TextFile> folderFiles) {

    public ScanContext(Path root, List<SourceFile> sources, List<ConfigFile> configFiles, List<TextFile> textFiles) {
        this(root, sources, configFiles, textFiles, textFiles);
    }
}
