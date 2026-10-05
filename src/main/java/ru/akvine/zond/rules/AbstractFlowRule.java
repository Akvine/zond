package ru.akvine.zond.rules;

import ru.akvine.zond.enums.Confidence;
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

    // Значение, вычисленное по всем путям, - факт; "на одном из путей" - только вероятность
    private static final Set<FlowAnalysis.Kind> CERTAIN = Set.of(
            FlowAnalysis.Kind.NULL_DEREFERENCE, FlowAnalysis.Kind.CONSTANT_CONDITION,
            FlowAnalysis.Kind.UNREACHABLE_CODE, FlowAnalysis.Kind.DIVISION_BY_ZERO);

    @Override
    public Confidence confidence() {
        return CERTAIN.containsAll(kinds()) ? Confidence.CONFIRMED : Confidence.PROBABLE;
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        return FlowAnalysis.of(sourceFiles).findings(kinds()).stream()
                .map(located -> violation(located.file(), located.finding().node(), located.finding().message()))
                .toList();
    }
}
