package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
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
public class DisabledTestWithoutReasonRule extends AbstractRule {
    // JUnit 5 и JUnit 4
    private static final Set<String> DISABLING_ANNOTATIONS = Set.of("Disabled", "Ignore");

    @Override
    public String code() {
        return RuleCodes.DISABLED_TEST_WITHOUT_REASON_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет отключенные тесты без указания причины";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(AnnotationExpr.class).stream()
                .filter(annotation -> DISABLING_ANNOTATIONS.contains(annotation.getName().getIdentifier()))
                .filter(annotation -> !hasReason(annotation))
                .map(annotation -> violation(sourceFile, annotation,
                        "@" + annotation.getNameAsString() + " без причины: через месяц никто не вспомнит, почему"
                                + " тест отключен и можно ли его вернуть; укажите причину или номер задачи:"
                                + " @Disabled(\"...\")"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    private boolean hasReason(AnnotationExpr annotation) {
        return annotation.findAll(StringLiteralExpr.class).stream()
                .anyMatch(reason -> !reason.asString().isBlank());
    }
}
