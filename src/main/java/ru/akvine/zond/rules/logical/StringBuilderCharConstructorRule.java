package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class StringBuilderCharConstructorRule extends AbstractRule {
    private static final Set<String> BUILDERS = Set.of("StringBuilder", "StringBuffer");
    private static final Set<String> CHAR_TYPES = Set.of("char", "Character");

    @Override
    public String code() {
        return RuleCodes.STRING_BUILDER_CHAR_CONSTRUCTOR_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет new StringBuilder с символом: он становится размером буфера, а не началом строки";
    }

    @Override
    public Confidence confidence() {
        return Confidence.CONFIRMED;
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ObjectCreationExpr creation : sourceFile.unit().findAll(ObjectCreationExpr.class)) {
            String type = creation.getType().getNameAsString();
            if (!BUILDERS.contains(type) || creation.getArguments().size() != 1) {
                continue;
            }
            Expression argument = Nodes.unwrap(creation.getArgument(0));
            boolean isChar = argument.isCharLiteralExpr() || argument.isNameExpr() && LocalTypes.findDeclaration(argument)
                    .flatMap(LocalTypes::declaredType)
                    .filter(CHAR_TYPES::contains)
                    .isPresent();
            if (isChar) {
                violations.add(violation(sourceFile, creation,
                        "В new " + type + "(" + argument + ") передан символ: он превращается в число и задает"
                                + " размер буфера, а строка остается пустой; передайте строку (\"...\") либо"
                                + " добавьте символ через append"));
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
