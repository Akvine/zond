package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.List;

@Component
public class UnsafeDeserializationRule extends AbstractRule {
    private static final String OBJECT_INPUT_STREAM = "ObjectInputStream";

    @Override
    public String code() {
        return RuleCodes.UNSAFE_DESERIALIZATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет десериализацию Java-объектов через ObjectInputStream";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(ObjectCreationExpr.class).stream()
                .filter(creation -> OBJECT_INPUT_STREAM.equals(creation.getType().getNameAsString()))
                .map(creation -> violation(sourceFile, creation,
                        "Десериализация через ObjectInputStream: если данные приходят извне, подобранный поток"
                                + " байтов выполнит произвольный код еще до проверки типа; используйте JSON"
                                + " либо ограничьте допустимые классы через ObjectInputFilter"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }
}
