package ru.akvine.zond.rules;

import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;

/**
 * Правило, которому нужно знать, откуда пришло значение: данные запроса прослеживаются через переменные
 * и вызовы методов по всему проекту, поэтому проверяются все файлы разом
 */
public abstract class AbstractTaintRule extends AbstractRule implements ProjectRule {

    /**
     * @param taint отвечает, попадают ли в выражение данные клиента
     */
    protected abstract List<Violation> check(SourceFile sourceFile, Taint taint);

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        Taint taint = Taint.of(sourceFiles);
        return sourceFiles.stream().flatMap(sourceFile -> check(sourceFile, taint).stream()).toList();
    }
}
