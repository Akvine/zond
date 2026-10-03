package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;

@Component
public class CheckTooManyDependenciesRule extends AbstractRule {
    private static final int MAX_DEPENDENCIES = 7;

    @Override
    public String code() {
        return RuleCodes.CHECK_TOO_MANY_DEPENDENCIES_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет Spring-бины, в которые внедряется больше " + MAX_DEPENDENCIES + " зависимостей";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (!SpringBeans.isBean(type)) {
                continue;
            }

            int dependencies = SpringBeans.findDependencies(type).size();
            if (dependencies > MAX_DEPENDENCIES) {
                violations.add(violation(sourceFile, type,
                        "В бин '" + type.getNameAsString() + "' внедряется " + dependencies + " зависимостей при"
                                + " допустимых " + MAX_DEPENDENCIES + ": класс делает слишком много; выделите"
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
