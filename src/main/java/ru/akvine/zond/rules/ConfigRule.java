package ru.akvine.zond.rules;

import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;

/**
 * Правило для файлов настроек (application.properties / application.yml), а не для Java-кода
 */
public interface ConfigRule extends Rule {

    /**
     * Проверяет один файл настроек.
     *
     * @return найденные нарушения или пустой список
     */
    List<Violation> checkConfig(ConfigFile configFile);

    @Override
    default List<Violation> check(SourceFile sourceFile) {
        return List.of();
    }
}
