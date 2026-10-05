package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.MethodDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Queries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class ModifyingQueryMisuseRule extends AbstractRule {
    private static final String MODIFYING = "Modifying";
    private static final Pattern CHANGING_QUERY =
            Pattern.compile("^\\s*(update|delete|insert)\\b.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // Изменяющий запрос возвращает число затронутых строк либо ничего
    private static final Set<String> ALLOWED_RESULT_TYPES = Set.of("void", "int", "Integer", "long", "Long");

    @Override
    public String code() {
        return RuleCodes.MODIFYING_QUERY_MISUSE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет изменяющие @Query без @Modifying и @Modifying с недопустимым типом результата";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            Optional<String> query = Queries.find(method).flatMap(Queries::text);
            boolean changes = query.filter(text -> CHANGING_QUERY.matcher(text).matches()).isPresent();
            boolean modifying = Annotations.has(method, MODIFYING);
            String name = method.getNameAsString();

            if (changes && !modifying) {
                violations.add(violation(sourceFile, method,
                        "Запрос метода '" + name + "' меняет данные, а @Modifying на нем нет: Spring Data выполнит"
                                + " его как выборку и упадет с ошибкой; добавьте @Modifying"));
            } else if (modifying && !ALLOWED_RESULT_TYPES.contains(LocalTypes.typeName(method.getType()))) {
                violations.add(violation(sourceFile, method,
                        "@Modifying-метод '" + name + "' возвращает " + method.getType() + ": изменяющий запрос"
                                + " может вернуть только число затронутых строк (int) либо void - с другим типом"
                                + " репозиторий не создастся"));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }
}
