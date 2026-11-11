package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.enums.Confidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * @SequenceGenerator(allocationSize = 1): за каждым новым идентификатором Hibernate идет в базу отдельным
 * запросом. Вставка тысячи строк - тысяча лишних обращений к последовательности.
 */
@Component
public class SequenceAllocationSizeOneRule extends AbstractRule {
    private static final Set<String> GENERATORS = Set.of("SequenceGenerator", "TableGenerator");
    private static final String ALLOCATION_SIZE = "allocationSize";
    private static final String ONE = "1";

    @Override
    public String code() {
        return RuleCodes.SEQUENCE_ALLOCATION_SIZE_ONE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет генераторы идентификаторов с allocationSize = 1: каждый новый идентификатор"
                + " - отдельный запрос к базе";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (AnnotationExpr annotation : sourceFile.unit().findAll(AnnotationExpr.class)) {
            if (!GENERATORS.contains(annotation.getName().getIdentifier()) || !annotation.isNormalAnnotationExpr()) {
                continue;
            }
            for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                if (ALLOCATION_SIZE.equals(pair.getNameAsString()) && ONE.equals(pair.getValue().toString())) {
                    violations.add(violation(sourceFile, pair,
                            "allocationSize = 1: за каждым новым идентификатором Hibernate идет в базу отдельным"
                                    + " запросом, и вставка тысячи строк стоит тысячу лишних обращений; задайте"
                                    + " allocationSize 50 или больше и такой же INCREMENT BY у последовательности"));
                }
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    // Для таблицы, в которую пишут редко, лишний запрос не заметен - по коду этого не видно
    @Override
    public Confidence confidence() {
        return Confidence.PROBABLE;
    }
}
