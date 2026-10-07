package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;

@Component
public class SerializableWithoutSerialVersionUidRule extends AbstractRule {
    private static final String SERIALIZABLE = "Serializable";
    private static final String SERIAL_VERSION_UID = "serialVersionUID";

    @Override
    public String code() {
        return RuleCodes.SERIALIZABLE_WITHOUT_SERIAL_VERSION_UID_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет классы Serializable без поля serialVersionUID";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            boolean serializable = type.getImplementedTypes().stream()
                    .anyMatch(implemented -> SERIALIZABLE.equals(implemented.getNameAsString()));
            if (type.isInterface() || !serializable || type.getFieldByName(SERIAL_VERSION_UID).isPresent()
                    || TestClasses.isInside(type)) {
                continue;
            }
            violations.add(violation(sourceFile, type,
                    "Класс '" + type.getNameAsString() + "' объявлен Serializable без serialVersionUID: номер версии"
                            + " вычисляется по составу класса, и после любой правки ранее сохраненные объекты"
                            + " (сессии, кэш, очереди) перестанут читаться с InvalidClassException; объявите"
                            + " private static final long serialVersionUID"));
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
