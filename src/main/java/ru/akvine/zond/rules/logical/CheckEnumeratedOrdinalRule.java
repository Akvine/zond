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

@Component
public class CheckEnumeratedOrdinalRule extends AbstractRule {
    private static final String ENUMERATED = "Enumerated";
    private static final String ORDINAL = "ORDINAL";

    @Override
    public String code() {
        return RuleCodes.CHECK_ENUMERATED_ORDINAL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет enum-поля сущностей, которые хранятся по порядковому номеру";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(AnnotationExpr.class).stream()
                .filter(annotation -> ENUMERATED.equals(annotation.getName().getIdentifier()))
                .filter(this::isOrdinal)
                .map(annotation -> violation(sourceFile, annotation,
                        "'" + annotation + "' хранит enum по порядковому номеру: добавление или перестановка"
                                + " значений enum молча изменит смысл уже сохраненных данных;"
                                + " используйте @Enumerated(EnumType.STRING)"))
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

    // @Enumerated без значения - это тоже ORDINAL, он используется по умолчанию
    private boolean isOrdinal(AnnotationExpr annotation) {
        return annotation.isMarkerAnnotationExpr() || annotation.toString().contains(ORDINAL);
    }
}
