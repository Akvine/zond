package ru.akvine.zond.rules.performance;

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
public class IdentityGenerationRule extends AbstractRule {
    private static final String GENERATED_VALUE = "GeneratedValue";
    private static final String IDENTITY = "IDENTITY";

    @Override
    public String code() {
        return RuleCodes.IDENTITY_GENERATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет идентификаторы со стратегией GenerationType.IDENTITY";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(AnnotationExpr.class).stream()
                .filter(annotation -> GENERATED_VALUE.equals(annotation.getName().getIdentifier()))
                .filter(annotation -> annotation.toString().contains(IDENTITY))
                .map(annotation -> violation(sourceFile, annotation,
                        "GenerationType.IDENTITY: идентификатор выдает БД при вставке, поэтому Hibernate вынужден"
                                + " отправлять каждую запись отдельным запросом - пакетная вставка отключается;"
                                + " при массовых вставках используйте GenerationType.SEQUENCE"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }
}
