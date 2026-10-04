package ru.akvine.zond.rules;

import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.flow.FlowAnalysis;

import java.util.List;
import java.util.Set;

/**
 * Правило, которое берет находки из анализа потока данных. Сам анализ один на все такие правила
 * и выполняется один раз за сканирование
 */
public abstract class AbstractFlowRule extends AbstractRule implements ProjectRule {

    /**
     * @return виды находок анализа, о которых сообщает правило
     */
    protected abstract Set<FlowAnalysis.Kind> kinds();

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        return FlowAnalysis.of(sourceFiles).findings(kinds()).stream()
                .map(located -> violation(located.file(), located.finding().node(), located.finding().message()))
                .toList();
    }
}
