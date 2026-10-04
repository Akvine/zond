package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.List;

@Component
public class CheckUtilityClassConstructorRule extends AbstractRule {
    private static final String MAIN = "main";

    @Override
    public String code() {
        return RuleCodes.CHECK_UTILITY_CLASS_CONSTRUCTOR_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет классы из одних static-методов без приватного конструктора";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(this::isUtilityClass)
                .filter(type -> type.getConstructors().isEmpty() && !TestClasses.isInside(type))
                .map(type -> violation(sourceFile, type,
                        "Класс '" + type.getNameAsString() + "' состоит из одних static-методов, но его можно"
                                + " создать через new: объект такого класса бессмыслен; добавьте приватный"
                                + " конструктор либо @UtilityClass"))
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

    // Обычный класс без аннотаций и предков, в котором все методы и поля статические. Класс с аннотацией
    // (@UtilityClass, @Component, @SpringBootApplication) и класс с main устроены иначе
    private boolean isUtilityClass(ClassOrInterfaceDeclaration type) {
        return !type.isInterface()
                && !type.isAbstract()
                && type.getAnnotations().isEmpty()
                && type.getExtendedTypes().isEmpty()
                && type.getImplementedTypes().isEmpty()
                && !type.getMethods().isEmpty()
                && type.getMethods().stream().allMatch(MethodDeclaration::isStatic)
                && type.getMethods().stream().noneMatch(method -> MAIN.equals(method.getNameAsString()))
                && type.getFields().stream().allMatch(FieldDeclaration::isStatic);
    }
}
