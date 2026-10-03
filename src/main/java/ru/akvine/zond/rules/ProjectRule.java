package ru.akvine.zond.rules;

import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;

/**
 * Правило, которому нужен проект целиком: циклические зависимости, связи между классами из разных файлов.
 * Вызывается один раз на все сканирование, а не для каждого файла.
 */
public interface ProjectRule extends Rule {

    /**
     * Проверяет все исходные файлы разом.
     *
     * @return найденные нарушения или пустой список
     */
    List<Violation> checkProject(List<SourceFile> sourceFiles);

    @Override
    default List<Violation> check(SourceFile sourceFile) {
        return checkProject(List.of(sourceFile));
    }
}
