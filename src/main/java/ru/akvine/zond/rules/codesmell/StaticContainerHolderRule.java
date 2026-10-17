package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.SpringContainer;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Контекст Spring в статическом поле: {@code SpringContextHolder.getBean(Foo.class)}.
 * <p>
 * Через такой держатель бины достаются откуда угодно, в том числе из объектов, которыми контейнер не
 * управляет: сущностей, утилит, созданных через {@code new}. Это Service Locator вместо инверсии управления
 * на уровне всего приложения: зависимости скрыты, до запуска контекста поле равно null, а в тестах, где
 * поднято несколько контекстов, оно указывает на последний.
 */
@Component
public class StaticContainerHolderRule extends AbstractRule {
    private static final String SPRING_BOOT_APPLICATION = "SpringBootApplication";

    @Override
    public String code() {
        return RuleCodes.STATIC_CONTAINER_HOLDER_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет ApplicationContext и BeanFactory в статических полях: держатели контекста,"
                + " через которые бины берут из любого места";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (FieldDeclaration field : sourceFile.unit().findAll(FieldDeclaration.class)) {
            Optional<TypeDeclaration<?>> owner = field.findAncestor(TypeDeclaration.class).map(type -> (TypeDeclaration<?>) type);
            // Главный класс держит контекст, чтобы перезапустить приложение, - это не раздача бинов
            if (!SpringContainer.isStaticContainer(field) || TestClasses.isInside(field)
                    || owner.filter(type -> Annotations.has(type, SPRING_BOOT_APPLICATION)).isPresent()) {
                continue;
            }
            for (VariableDeclarator variable : field.getVariables()) {
                violations.add(violation(sourceFile, variable, message(variable, owner)));
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
        return ErrorType.CODE_SMELL;
    }

    private String message(VariableDeclarator variable, Optional<TypeDeclaration<?>> owner) {
        boolean handsOutBeans = owner.stream()
                .flatMap(type -> type.getMethods().stream())
                .filter(method -> method.isStatic())
                .flatMap(method -> method.findAll(MethodCallExpr.class).stream())
                .anyMatch(call -> call.getNameAsString().startsWith("getBean"));
        String via = handsOutBeans ? " и статический getBean(...)" : "";
        return "'" + variable.getTypeAsString() + " " + variable.getNameAsString() + "': контекст Spring хранится в"
                + " статическом поле" + via + " - бины можно достать из любого места, в том числе из объектов, которыми"
                + " контейнер не управляет; это Service Locator вместо инверсии управления: зависимости скрыты, до"
                + " запуска контекста поле равно null, а в тестах с несколькими контекстами указывает на последний"
                + " поднятый. Сделайте классы, которым нужны бины, бинами и внедряйте зависимости через"
                + " конструктор; объекту вне контейнера передавайте нужное явно";
    }
}
