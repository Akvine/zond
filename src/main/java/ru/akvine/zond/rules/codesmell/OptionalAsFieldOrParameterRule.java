package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class OptionalAsFieldOrParameterRule extends AbstractRule {
    private static final String OPTIONAL = "Optional";

    @Override
    public String code() {
        return RuleCodes.OPTIONAL_AS_FIELD_OR_PARAMETER_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет Optional в роли поля или параметра метода";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();

        for (FieldDeclaration field : sourceFile.unit().findAll(FieldDeclaration.class)) {
            for (VariableDeclarator variable : field.getVariables()) {
                if (OPTIONAL.equals(LocalTypes.typeName(variable.getType()))) {
                    violations.add(violation(sourceFile, variable,
                            "Optional в роли поля '" + variable.getNameAsString() + "': Optional не сериализуется"
                                    + " и создан для возвращаемых значений; храните обычное значение, а Optional"
                                    + " возвращайте из геттера"));
                }
            }
        }

        // Параметры лямбд не трогаем: их тип задает функциональный интерфейс
        for (Parameter parameter : sourceFile.unit().findAll(Parameter.class)) {
            Node owner = parameter.getParentNode().orElse(null);
            boolean isDeclared = owner instanceof MethodDeclaration || owner instanceof ConstructorDeclaration;
            if (isDeclared && OPTIONAL.equals(LocalTypes.typeName(parameter.getType()))) {
                violations.add(violation(sourceFile, parameter,
                        "Optional в роли параметра '" + parameter.getNameAsString() + "': вызывающему коду придется"
                                + " оборачивать значение, а сам параметр все равно может прийти null;"
                                + " сделайте перегрузку метода либо примите значение, допускающее null"));
            }
        }

        violations.sort(Comparator.comparingInt(Violation::line));
        return violations;
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
