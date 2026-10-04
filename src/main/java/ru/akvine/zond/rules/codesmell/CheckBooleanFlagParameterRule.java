package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class CheckBooleanFlagParameterRule extends AbstractRule {
    private static final String OVERRIDE = "Override";
    private static final Set<String> BOOLEAN_TYPES = Set.of("boolean", "Boolean");

    @Override
    public String code() {
        return RuleCodes.CHECK_BOOLEAN_FLAG_PARAMETER_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет методы с параметром-флагом boolean среди других параметров";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            // Единственный boolean-параметр - это сеттер или переключатель: setActive(true) читается ясно.
            // Приватные методы и переопределенные не трогаем
            if (method.getParameters().size() < 2 || method.isPrivate() || Annotations.has(method, OVERRIDE)) {
                continue;
            }

            for (Parameter parameter : method.getParameters()) {
                if (BOOLEAN_TYPES.contains(LocalTypes.typeName(parameter.getType()))) {
                    violations.add(violation(sourceFile, parameter,
                            "Параметр-флаг '" + parameter.getNameAsString() + "' у метода '"
                                    + method.getNameAsString() + "': вызов вида process(order, true) не объясняет,"
                                    + " что значит true, а метод делает два разных дела; разделите на два метода"
                                    + " с говорящими именами"));
                }
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.INFO;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }
}
