package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class OneToManyWithoutMappedByRule extends AbstractRule {
    private static final String ONE_TO_MANY = "OneToMany";
    private static final String MAPPED_BY = "mappedBy";

    // Явно заданная колонка или таблица связи: так задумано
    private static final Set<String> JOIN_ANNOTATIONS = Set.of("JoinColumn", "JoinColumns", "JoinTable");

    @Override
    public String code() {
        return RuleCodes.ONE_TO_MANY_WITHOUT_MAPPED_BY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет @OneToMany без mappedBy и без @JoinColumn";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (FieldDeclaration field : sourceFile.unit().findAll(FieldDeclaration.class)) {
            Optional<AnnotationExpr> relation = Annotations.find(field, ONE_TO_MANY);
            if (relation.isEmpty() || hasMappedBy(relation.get()) || Annotations.hasAny(field, JOIN_ANNOTATIONS)) {
                continue;
            }
            violations.add(violation(sourceFile, field,
                    "@OneToMany '" + fieldNames(field) + "' без mappedBy и без @JoinColumn: Hibernate заведет для"
                            + " связи отдельную таблицу и будет выполнять лишние запросы при каждом изменении"
                            + " коллекции; укажите mappedBy на поле @ManyToOne дочерней сущности"));
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

    private boolean hasMappedBy(AnnotationExpr annotation) {
        return annotation.isNormalAnnotationExpr()
                && annotation.asNormalAnnotationExpr().getPairs().stream()
                .anyMatch(pair -> MAPPED_BY.equals(pair.getNameAsString()));
    }

    private String fieldNames(FieldDeclaration field) {
        return field.getVariables().stream()
                .map(NodeWithSimpleName::getNameAsString)
                .collect(Collectors.joining(", "));
    }
}
