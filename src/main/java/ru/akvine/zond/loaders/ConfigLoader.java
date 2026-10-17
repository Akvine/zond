package ru.akvine.zond.loaders;

import ru.akvine.zond.models.ConfigFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
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
    default List<ConfigFile> load(Path root, Predicate<Path> included) {
        return load(root, included, Set.of());
    }

    /**
     * @param customNames файлы настроек, которые проект называет сам: имя из spring.config.name без расширения
     *                    (thingsboard - это thingsboard.yml и thingsboard-dev.yml) либо полное имя файла
     *                    из @PropertySource (recaptcha.properties)
     */
    List<ConfigFile> load(Path root, Predicate<Path> included, Set<String> customNames);
}
