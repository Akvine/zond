package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.MethodDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Optional;

@Component
public class CheckModifyingWithoutClearRule extends AbstractRule {
    private static final String MODIFYING = "Modifying";
    private static final String CLEAR_AUTOMATICALLY = "clearAutomatically = true";

    @Override
    public String code() {
        return RuleCodes.CHECK_MODIFYING_WITHOUT_CLEAR_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет @Modifying-запросы без clearAutomatically";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodDeclaration.class).stream()
                .filter(method -> Annotations.find(method, MODIFYING)
                        .filter(annotation -> !annotation.toString().contains(CLEAR_AUTOMATICALLY))
                        .isPresent())
                .map(method -> violation(sourceFile, method,
                        "@Modifying на методе '" + method.getNameAsString() + "' без clearAutomatically = true:"
                                + " запрос меняет строки в обход контекста Hibernate, и уже загруженные сущности"
                                + " остаются со старыми значениями; добавьте clearAutomatically = true"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }
}
