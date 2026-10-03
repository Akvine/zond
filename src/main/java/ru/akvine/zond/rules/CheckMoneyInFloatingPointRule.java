package ru.akvine.zond.rules;

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
import java.util.regex.Pattern;

@Component
public class CheckMoneyInFloatingPointRule extends AbstractRule {
    private static final Set<String> FLOATING_TYPES = Set.of("double", "float", "Double", "Float");

    // Типы не разрешаем: о том, что в поле деньги, судим по имени
    private static final Pattern MONEY_NAME = Pattern.compile(
            ".*(price|amount|cost|balance|salary|fee|payment|money|revenue|discount|tax).*|^(total|sum)$",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String code() {
        return RuleCodes.CHECK_MONEY_IN_FLOATING_POINT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет денежные поля типа double и float";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (FieldDeclaration field : sourceFile.unit().findAll(FieldDeclaration.class)) {
            for (VariableDeclarator variable : field.getVariables()) {
                String type = LocalTypes.typeName(variable.getType());
                if (FLOATING_TYPES.contains(type) && MONEY_NAME.matcher(variable.getNameAsString()).matches()) {
                    violations.add(violation(sourceFile, variable,
                            "Денежное поле '" + variable.getNameAsString() + "' типа " + type + ": двоичная дробь"
                                    + " не может точно представить 0.1, при сложении и округлении копейки"
                                    + " расходятся; используйте BigDecimal либо целое число минимальных единиц"));
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
