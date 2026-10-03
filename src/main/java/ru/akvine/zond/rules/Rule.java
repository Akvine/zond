package ru.akvine.zond.rules;

import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;

public interface Rule {

    String name();

    String code();

    String description();

    boolean enabled();

    /**
     * Проверяет один исходный файл.
     *
     * @return найденные нарушения или пустой список
     */
    List<Violation> check(SourceFile sourceFile);


    ErrorLevel errorLevel();

    ErrorType errorType();
}
