package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import ru.akvine.zond.config.RuleSettings;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.RuleParameter;
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

    private static final RuleParameter MAX_CALL_DEPTH = new RuleParameter(
            RuleSettings.MAX_CALL_DEPTH, 4,
            "На сколько вызовов вверх от метода искать, откуда пришло значение; 0 - только в самом методе");

    @Override
    public List<RuleParameter> parameters() {
        return List.of(MAX_CALL_DEPTH);
    }

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
        taint.limitDepth(value(MAX_CALL_DEPTH));
        return sourceFiles.stream().flatMap(sourceFile -> check(sourceFile, taint).stream()).toList();
    }
}
