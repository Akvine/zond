package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.flow.FlowAnalysis;
import ru.akvine.zond.rules.support.Nodes;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Правило, которое берет находки из анализа потока данных. Сам анализ один на все такие правила
 * и выполняется один раз за сканирование
 */
public abstract class AbstractFlowRule extends AbstractRule implements ProjectRule {

    /**
     * @return виды находок анализа, о которых сообщает правило
     */
    private static final String DECLARED_NOT_NULL = "параметр помечен @NonNull";
    // 'jar' может быть null (значение проверено на null на строке 12)
    private static final Pattern CHECKED_ELSEWHERE = Pattern.compile(
            "^'([\\w.]+)' может быть null \\(значение проверено на null на строке (\\d+)\\)");

    protected abstract Set<FlowAnalysis.Kind> kinds();

    // Значение, вычисленное по всем путям, - факт; "на одном из путей" - только вероятность
    private static final Set<FlowAnalysis.Kind> CERTAIN = Set.of(
            FlowAnalysis.Kind.NULL_DEREFERENCE, FlowAnalysis.Kind.CONSTANT_CONDITION,
            FlowAnalysis.Kind.UNREACHABLE_CODE, FlowAnalysis.Kind.DIVISION_BY_ZERO,
            FlowAnalysis.Kind.EMPTY_OPTIONAL);

    @Override
    public Confidence confidence() {
        return CERTAIN.containsAll(kinds()) ? Confidence.CONFIRMED : Confidence.PROBABLE;
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        return FlowAnalysis.of(sourceFiles).findings(kinds()).stream()
                .filter(located -> !isGuardedThroughVariable(located.finding()))
                .map(located -> {
                    FlowAnalysis.Finding finding = located.finding();
                    Violation violation = violation(located.file(), finding.node(), finding.message());
                    // Аннотация - обещание тех, кто вызывает метод, а не гарантия: защитную проверку такого
                    // параметра оставляют намеренно
                    if (finding.kind() == FlowAnalysis.Kind.CONSTANT_CONDITION
                            && finding.message().contains(DECLARED_NOT_NULL)) {
                        return violation.withConfidence(Confidence.SUSPICION);
                    }
                    // Часть находок анализ считает достовернее, чем правило в целом
                    Confidence own = finding.confidence();
                    return own == null ? violation : violation.withConfidence(own);
                })
                .toList();
    }

    /**
     * Проверка на null спрятана в значении другой переменной, и к объекту обращаются только под ее защитой:
     * <pre>
     * JarEntry entry = name != null && jar != null ? jar.getJarEntry(name) : null;
     * if (entry != null) { jar.getInputStream(entry); }
     * </pre>
     * Анализ хода выполнения такую связь двух переменных не видит и считает, что jar может быть null
     */
    private boolean isGuardedThroughVariable(FlowAnalysis.Finding finding) {
        Matcher evidence = CHECKED_ELSEWHERE.matcher(finding.message());
        if (finding.kind() != FlowAnalysis.Kind.POSSIBLE_NULL_DEREFERENCE || !evidence.find()) {
            return false;
        }
        String checked = evidence.group(1);
        int line = Integer.parseInt(evidence.group(2));
        Node use = finding.node();
        return Nodes.enclosingCallable(use).stream()
                .flatMap(callable -> callable.findAll(BinaryExpr.class).stream())
                .filter(check -> check.getBegin().filter(position -> position.line == line).isPresent()
                        && check.toString().contains(checked))
                .map(check -> check.findAncestor(VariableDeclarator.class))
                .flatMap(Optional::stream)
                .anyMatch(guard -> isUnderGuard(use, guard.getNameAsString()));
    }

    // Обращение стоит внутри if с этой переменной в условии либо после if с ней, который завершает метод
    private boolean isUnderGuard(Node use, String guard) {
        Node current = use;
        Node parent = use.getParentNode().orElse(null);
        while (parent != null) {
            if (parent instanceof IfStmt condition && current != condition.getCondition()
                    && mentions(condition.getCondition(), guard)) {
                return true;
            }
            if (parent instanceof BlockStmt block && exitsEarlierOn(block, current, guard)) {
                return true;
            }
            current = parent;
            parent = parent.getParentNode().orElse(null);
        }
        return false;
    }

    private boolean exitsEarlierOn(BlockStmt block, Node current, String guard) {
        for (Statement statement : block.getStatements()) {
            if (statement == current) {
                return false;
            }
            if (statement instanceof IfStmt check && mentions(check.getCondition(), guard)
                    && check.getThenStmt().findFirst(Statement.class, exit -> exit.isReturnStmt() || exit.isThrowStmt()
                    || exit.isContinueStmt() || exit.isBreakStmt()).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private boolean mentions(Node condition, String variable) {
        return condition.findFirst(NameExpr.class, name -> name.getNameAsString().equals(variable)).isPresent();
    }
}
