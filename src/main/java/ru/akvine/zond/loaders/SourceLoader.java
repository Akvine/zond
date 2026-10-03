package ru.akvine.zond.loaders;

import ru.akvine.zond.models.LoadResult;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

public interface SourceLoader {

    /**
     * @param root .java файл или директория, которая обходится рекурсивно
     */
    default LoadResult load(Path root) {
        return load(root, path -> true);
    }

    /**
     * @param root     .java файл или директория, которая обходится рекурсивно
     * @param included какие из найденных файлов разбирать; остальные пропускаются до разбора
     */
    default LoadResult load(Path root, Predicate<Path> included) {
        return load(root, included, List.of());
    }

    /**
     * @param root      .java файл или директория, которая обходится рекурсивно
     * @param included  какие из найденных файлов разбирать; остальные пропускаются до разбора
     * @param classpath jar-файлы и папки с jar-файлами: библиотеки проекта, по которым разрешаются типы
     */
    LoadResult load(Path root, Predicate<Path> included, List<Path> classpath);
}
