package ru.akvine.zond.loaders;

import ru.akvine.zond.models.TextFile;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

public interface TextFileLoader {

    /**
     * @param root     файл или директория, которая обходится рекурсивно
     * @param included какие из найденных файлов читать
     * @return SQL-миграции, файлы сборки, Dockerfile и файлы сообщений
     */
    List<TextFile> load(Path root, Predicate<Path> included);
}
