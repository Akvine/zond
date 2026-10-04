package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.JpaEntities;

import java.util.List;
import java.util.Set;

@Component
public class CheckEntityWithoutNoArgsConstructorRule extends AbstractRule {
    // Lombok: одни аннотации создают конструктор без аргументов, другие - только конструктор с аргументами
    private static final String NO_ARGS_CONSTRUCTOR = "NoArgsConstructor";
    private static final Set<String> ARGS_CONSTRUCTORS = Set.of("AllArgsConstructor", "RequiredArgsConstructor");

    @Override
    public String code() {
        return RuleCodes.CHECK_ENTITY_WITHOUT_NO_ARGS_CONSTRUCTOR_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет сущности без конструктора без аргументов";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return JpaEntities.findEntities(sourceFile.unit()).stream()
                .filter(entity -> !hasNoArgsConstructor(entity))
                .map(entity -> violation(sourceFile, entity,
                        "Сущность '" + entity.getNameAsString() + "' без конструктора без аргументов: JPA создает"
                                + " сущности через него, без него приложение упадет при первой загрузке из БД;"
                                + " добавьте protected-конструктор без аргументов"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private boolean hasNoArgsConstructor(ClassOrInterfaceDeclaration entity) {
        if (Annotations.has(entity, NO_ARGS_CONSTRUCTOR)) {
            return true;
        }

        // Если конструкторов нет совсем, компилятор сам добавит конструктор без аргументов
        boolean hasConstructors = !entity.getConstructors().isEmpty()
                || Annotations.hasAny(entity, ARGS_CONSTRUCTORS);
        return !hasConstructors
                || entity.getConstructors().stream().anyMatch(constructor -> constructor.getParameters().isEmpty());
    }
}
