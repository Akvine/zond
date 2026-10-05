package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.List;

@Component
public class ConstantInterfaceRule extends AbstractRule {
    @Override
    public String code() {
        return RuleCodes.CONSTANT_INTERFACE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет интерфейсы, в которых нет ничего, кроме констант";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(type -> type.isInterface() && !type.getFields().isEmpty())
                .filter(type -> type.getMembers().stream().allMatch(member -> member.isFieldDeclaration()))
                .map(type -> violation(sourceFile, type,
                        "Интерфейс '" + type.getNameAsString() + "' состоит из одних констант: интерфейс задает"
                                + " поведение, а константы становятся частью каждого класса, который его"
                                + " реализует; используйте final-класс с приватным конструктором либо enum"))
                .toList();
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
