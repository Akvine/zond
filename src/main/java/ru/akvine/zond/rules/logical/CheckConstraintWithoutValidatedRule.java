package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
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
import ru.akvine.zond.rules.support.Constraints;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckConstraintWithoutValidatedRule extends AbstractRule {
    // Контроллеры сюда не входят: тело запроса с @Valid проверяет сам Spring MVC
    private static final Set<String> BEAN_ANNOTATIONS = Set.of("Service", "Component", "Repository");

    @Override
    public String code() {
        return RuleCodes.CHECK_CONSTRAINT_WITHOUT_VALIDATED_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет ограничения на параметрах методов бина без @Validated на классе";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (!Annotations.hasAny(type, BEAN_ANNOTATIONS) || Annotations.has(type, Constraints.VALIDATED)) {
                continue;
            }
            // Об одном классе сообщаем один раз - на первом методе с ограничениями
            for (MethodDeclaration method : type.getMethods()) {
                Optional<Parameter> constrained = method.getParameters().stream()
                        .filter(parameter -> Constraints.hasAny(parameter) || Annotations.has(parameter, Constraints.VALID))
                        .findFirst();
                if (constrained.isPresent() && !method.isPrivate()) {
                    violations.add(violation(sourceFile, constrained.get(),
                            "Ограничения на параметре '" + constrained.get().getNameAsString() + "' метода '"
                                    + method.getNameAsString() + "' не проверяются: на классе '"
                                    + type.getNameAsString() + "' нет @Validated, и Spring вызовы его методов"
                                    + " не перехватывает; добавьте @Validated на класс"));
                    break;
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
