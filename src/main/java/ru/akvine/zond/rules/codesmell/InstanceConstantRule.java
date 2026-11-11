package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;

/**
 * private final int MAX_SIZE = 200; без static: значение у всех объектов одно, а место под него отводится
 * в каждом, и как Validator.MAX_SIZE его не прочитать.
 */
@Component
public class InstanceConstantRule extends AbstractRule {

    @Override
    public String code() {
        return RuleCodes.INSTANCE_CONSTANT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет final-поля со значением-литералом, объявленные без static";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (FieldDeclaration field : sourceFile.unit().findAll(FieldDeclaration.class)) {
            // Поле с аннотацией (@Value, @Builder.Default, @Column) заполняет или читает фреймворк
            if (field.isStatic() || !field.isFinal() || !field.getAnnotations().isEmpty() || !isInClass(field)
                    || TestClasses.isInside(field)) {
                continue;
            }
            for (VariableDeclarator variable : field.getVariables()) {
                if (variable.getInitializer().filter(this::isLiteral).isPresent()) {
                    violations.add(violation(sourceFile, variable,
                            "Поле '" + variable.getNameAsString() + "' - константа, но объявлено без static: значение"
                                    + " у всех объектов одно, а хранится в каждом; объявите его static final"));
                }
            }
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

    // У интерфейса поля и так static, у анонимного класса static-поле до Java 16 объявить нельзя
    private boolean isInClass(FieldDeclaration field) {
        return field.getParentNode()
                .filter(parent -> parent instanceof EnumDeclaration
                        || parent instanceof ClassOrInterfaceDeclaration type && !type.isInterface())
                .isPresent();
    }

    // "text", 200, -1, 1.5, 'c', true. null и вызовы - не константа
    private boolean isLiteral(Expression initializer) {
        Expression value = Nodes.unwrap(initializer);
        if (value.isUnaryExpr()) {
            value = Nodes.unwrap(value.asUnaryExpr().getExpression());
        }
        return value.isLiteralExpr() && !value.isNullLiteralExpr();
    }
}
