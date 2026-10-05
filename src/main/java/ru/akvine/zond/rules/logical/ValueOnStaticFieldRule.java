package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.FieldDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;

import java.util.List;

@Component
public class ValueOnStaticFieldRule extends AbstractRule {
    private static final String VALUE = "Value";

    @Override
    public String code() {
        return RuleCodes.VALUE_ON_STATIC_FIELD_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет @Value на static-полях: Spring в них значение не подставляет";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(FieldDeclaration.class).stream()
                .filter(field -> field.isStatic() && Annotations.has(field, VALUE))
                .map(field -> violation(sourceFile, field,
                        "@Value на static-поле '" + field.getVariable(0).getNameAsString() + "': Spring заполняет"
                                + " поля экземпляра, статическое останется пустым; уберите static либо задавайте"
                                + " значение через нестатический сеттер"))
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
}
