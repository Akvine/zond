package ru.akvine.zond.loaders;

import ru.akvine.zond.models.ConfigFile;

import java.nio.file.Path;
import java.util.List;

public interface ConfigLoader {

    /**
     * @param root файл настроек или директория, которая обходится рекурсивно
     * @return файлы настроек Spring: application*.properties, application*.yml, bootstrap*
     */
    List<ConfigFile> load(Path root);
}
