package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckEagerFetchRule extends AbstractRule {
    private static final String FETCH = "fetch";
    private static final String EAGER = "EAGER";

    // У этих связей EAGER действует по умолчанию, если fetch не указан
    private static final Set<String> EAGER_BY_DEFAULT = Set.of("ManyToOne", "OneToOne");

    @Override
    public String code() {
        return RuleCodes.CHECK_EAGER_FETCH_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет связи с FetchType.EAGER, заданным явно или действующим по умолчанию";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (AnnotationExpr annotation : sourceFile.unit().findAll(AnnotationExpr.class)) {
            String name = annotation.getName().getIdentifier();
            if (!JpaEntities.RELATION_ANNOTATIONS.contains(name)) {
                continue;
            }

            Optional<Expression> fetch = findFetch(annotation);
            if (fetch.filter(value -> value.toString().endsWith(EAGER)).isPresent()) {
                violations.add(violation(sourceFile, annotation,
                        "@" + name + " с FetchType.EAGER: связь загружается при каждом чтении сущности, даже когда"
                                + " она не нужна, а в списках дает лишние запросы; используйте FetchType.LAZY"
                                + " и подгружайте связь там, где она требуется"));
            } else if (fetch.isEmpty() && EAGER_BY_DEFAULT.contains(name)) {
                violations.add(violation(sourceFile, annotation,
                        "@" + name + " без fetch: по умолчанию это FetchType.EAGER, связь загружается при каждом"
                                + " чтении сущности; укажите fetch = FetchType.LAZY"));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    private Optional<Expression> findFetch(AnnotationExpr annotation) {
        if (!annotation.isNormalAnnotationExpr()) {
            return Optional.empty();
        }
        return annotation.asNormalAnnotationExpr().getPairs().stream()
                .filter(pair -> FETCH.equals(pair.getNameAsString()))
                .map(pair -> pair.getValue())
                .findFirst();
    }
}
