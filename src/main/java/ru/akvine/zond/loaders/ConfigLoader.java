package ru.akvine.zond.loaders;

import ru.akvine.zond.models.ConfigFile;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

public interface ConfigLoader {

    /**
     * @param root файл настроек или директория, которая обходится рекурсивно
     * @return файлы настроек Spring: application*.properties, application*.yml, bootstrap*
     */
    default List<ConfigFile> load(Path root) {
        return load(root, path -> true);
    }

    /**
     * @param root     файл настроек или директория, которая обходится рекурсивно
     * @param included какие из найденных файлов читать
     * @return файлы настроек Spring: application*.properties, application*.yml, bootstrap*
     */
    List<ConfigFile> load(Path root, Predicate<Path> included);
}
