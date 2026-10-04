package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.JpaEntities;

import java.util.ArrayList;
import java.util.List;

@Component
public class CheckEntityFinalMethodRule extends AbstractRule {

    @Override
    public String code() {
        return RuleCodes.CHECK_ENTITY_FINAL_METHOD_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет final классы-сущности и final методы сущностей";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration entity : JpaEntities.findEntities(sourceFile.unit())) {
            // От final класса прокси не создать вообще - отдельные методы уже не важны
            if (entity.isFinal()) {
                violations.add(violation(sourceFile, entity,
                        "Сущность '" + entity.getNameAsString() + "' объявлена final: Hibernate создает прокси"
                                + " для LAZY-загрузки наследованием, от final класса это невозможно; уберите final"));
                continue;
            }

            for (MethodDeclaration method : entity.getMethods()) {
                if (method.isFinal() && !method.isStatic() && !method.isPrivate()) {
                    violations.add(violation(sourceFile, method,
                            "final метод '" + method.getNameAsString() + "' сущности '" + entity.getNameAsString()
                                    + "': прокси не может его переопределить, на незагруженной LAZY-сущности метод"
                                    + " отработает по пустым полям; уберите final"));
                }
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
        return ErrorType.LOGICAL;
    }
}
