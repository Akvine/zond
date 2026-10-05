package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.NameExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class UnusedMockRule extends AbstractRule {
    private static final String MOCK = "Mock";
    private static final String INJECT_MOCKS = "InjectMocks";

    @Override
    public String code() {
        return RuleCodes.UNUSED_MOCK_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует тесты и ищет @Mock-поля, которые не настроены, не проверены и никуда не внедрены";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            // @InjectMocks подставляет моки в проверяемый объект сам - имя поля в тесте может не встретиться
            if (type.getFields().stream().anyMatch(field -> Annotations.has(field, INJECT_MOCKS))) {
                continue;
            }
            Set<String> usedNames = type.findAll(NameExpr.class).stream()
                    .map(NameExpr::getNameAsString)
                    .collect(Collectors.toSet());
            for (FieldDeclaration field : type.getFields()) {
                if (!Annotations.has(field, MOCK)) {
                    continue;
                }
                for (VariableDeclarator variable : field.getVariables()) {
                    if (!usedNames.contains(variable.getNameAsString())) {
                        violations.add(violation(sourceFile, variable,
                                "Мок '" + variable.getNameAsString() + "' создан, но в тесте не используется:"
                                        + " он не настроен, не проверен и никуда не передан; удалите его"));
                    }
                }
            }
        }
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
