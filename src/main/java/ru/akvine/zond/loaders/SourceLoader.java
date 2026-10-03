package ru.akvine.zond.loaders;

import ru.akvine.zond.models.LoadResult;

import java.nio.file.Path;

public interface SourceLoader {

    /**
     * @param root .java файл или директория, которая обходится рекурсивно
     */
    LoadResult load(Path root);
}
