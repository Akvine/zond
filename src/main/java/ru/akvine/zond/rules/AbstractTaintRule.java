package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.support.Taint;

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

    // Отслеживание данных текущего сканирования: по нему находка узнает свою уверенность
    private Taint current;

    @Override
    public Confidence confidence() {
        return Confidence.CONFIRMED;
    }

    // Находка достоверна настолько, насколько достоверен источник, найденный перед ней: данные запроса -
    // подтверждено, сообщение из очереди или ответ другого сервиса - вероятно, источник не найден - подозрение
    @Override
    protected Violation violation(SourceFile sourceFile, Node node, String message) {
        Violation violation = super.violation(sourceFile, node, message);
        return current == null ? violation : violation.withConfidence(current.takeConfidence());
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        Taint taint = Taint.of(sourceFiles);
        current = taint;
        taint.takeConfidence();
        return sourceFiles.stream().flatMap(sourceFile -> check(sourceFile, taint).stream()).toList();
    }
}
