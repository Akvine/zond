package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.RuleParameter;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.SpringBeans;

import java.util.ArrayList;
import java.util.List;

@Component
public class TooManyDependenciesRule extends AbstractRule {
    private static final RuleParameter MAX_DEPENDENCIES =
            new RuleParameter("max-dependencies", 7, "Допустимое число зависимостей бина");

    @Override
    public List<RuleParameter> parameters() {
        return List.of(MAX_DEPENDENCIES);
    }

    @Override
    public String code() {
        return RuleCodes.TOO_MANY_DEPENDENCIES_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет Spring-бины, в которые внедряется больше " + value(MAX_DEPENDENCIES)
                + " зависимостей";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (!SpringBeans.isBean(type)) {
                continue;
            }

            int dependencies = SpringBeans.findDependencies(type).size();
            if (dependencies > value(MAX_DEPENDENCIES)) {
                violations.add(violation(sourceFile, type,
                        "В бин '" + type.getNameAsString() + "' внедряется " + dependencies + " зависимостей при"
                                + " допустимых " + value(MAX_DEPENDENCIES) + ": класс делает слишком много; выделите"
                                + " часть обязанностей в отдельные бины"));
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
        return ErrorType.CODE_SMELL;
    }
}
