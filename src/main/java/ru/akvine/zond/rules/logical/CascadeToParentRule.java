package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.AnnotationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.List;
import java.util.Set;

@Component
public class CascadeToParentRule extends AbstractRule {
    private static final String CASCADE = "cascade";

    // У этих связей на другой стороне - общая сущность, которой владеют и другие объекты
    private static final Set<String> SHARED_SIDE_RELATIONS = Set.of("ManyToOne", "ManyToMany");
    private static final List<String> REMOVING_CASCADES = List.of("ALL", "REMOVE");

    @Override
    public String code() {
        return RuleCodes.CASCADE_TO_PARENT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет каскадное удаление на связях @ManyToOne и @ManyToMany";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(AnnotationExpr.class).stream()
                .filter(annotation -> SHARED_SIDE_RELATIONS.contains(annotation.getName().getIdentifier()))
                .filter(this::cascadesRemoval)
                .map(annotation -> violation(sourceFile, annotation,
                        "Каскадное удаление на @" + annotation.getNameAsString() + ": удаление одной записи удалит"
                                + " и связанную сущность, на которую ссылаются другие записи; уберите REMOVE / ALL,"
                                + " оставьте PERSIST и MERGE, если они нужны"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    // cascade = CascadeType.ALL, cascade = {CascadeType.PERSIST, CascadeType.REMOVE}
    private boolean cascadesRemoval(AnnotationExpr annotation) {
        return annotation.isNormalAnnotationExpr()
                && annotation.asNormalAnnotationExpr().getPairs().stream()
                .filter(pair -> CASCADE.equals(pair.getNameAsString()))
                .map(pair -> pair.getValue().toString())
                .anyMatch(value -> REMOVING_CASCADES.stream().anyMatch(type -> value.contains("." + type)
                        || value.equals(type)));
    }
}
