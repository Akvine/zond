package ru.akvine.zond.rules;

import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;

/**
 * Правило, которому мало Java-кода: проверяет SQL-миграции, файлы сборки, Dockerfile, файлы сообщений
 * либо сверяет код с настройками. Вызывается один раз на все сканирование.
 */
public interface ContextRule extends Rule {

    /**
     * Проверяет все загруженное разом.
     *
     * @return найденные нарушения или пустой список
     */
    List<Violation> checkContext(ScanContext context);

    @Override
    default List<Violation> check(SourceFile sourceFile) {
        return List.of();
    }
}
