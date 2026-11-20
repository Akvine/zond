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

    /**
     * Папка с несколькими независимыми проектами проверяется по частям, а файл настроек над ними (в общем
     * корне) достается каждой части. Правило, которое судит о таком файле по тому, чего в коде нет ("свойство
     * никто не читает"), право, только когда находку дала каждая часть: одной из них свойство может быть нужно
     *
     * @return true, если находка в общем файле должна подтвердиться во всех частях проекта
     */
    default boolean judgesByAbsenceInCode() {
        return false;
    }

    @Override
    default List<Violation> check(SourceFile sourceFile) {
        return List.of();
    }
}
