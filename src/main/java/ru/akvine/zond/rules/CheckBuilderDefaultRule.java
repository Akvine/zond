package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class CheckBuilderDefaultRule extends AbstractRule {
    private static final Set<String> BUILDER_ANNOTATIONS = Set.of("Builder", "SuperBuilder");
    private static final String BUILDER_DEFAULT = "Builder.Default";

    @Override
    public String code() {
        return RuleCodes.CHECK_BUILDER_DEFAULT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет поля со значением по умолчанию в классах с @Builder без @Builder.Default";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (!Annotations.hasAny(type, BUILDER_ANNOTATIONS)) {
                continue;
            }
            for (FieldDeclaration field : type.getFields()) {
                // Статические поля в builder не попадают, final с инициализатором - тоже
                boolean hasDefault = field.getAnnotations().stream()
                        .anyMatch(annotation -> annotation.getNameAsString().equals(BUILDER_DEFAULT));
                if (field.isStatic() || field.isFinal() || hasDefault) {
                    continue;
                }
                for (VariableDeclarator variable : field.getVariables()) {
                    if (variable.getInitializer().isPresent()) {
                        violations.add(violation(sourceFile, variable,
                                "Значение по умолчанию поля '" + variable.getNameAsString() + "' теряется при"
                                        + " сборке через builder: Lombok игнорирует инициализатор, поле получит"
                                        + " null, 0 или false; пометьте поле @Builder.Default"));
                    }
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
