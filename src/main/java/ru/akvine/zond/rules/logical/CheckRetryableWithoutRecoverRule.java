package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
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
import java.util.regex.Pattern;

@Component
public class CheckRetryableWithoutRecoverRule extends AbstractRule {
    private static final String RETRYABLE = "Retryable";
    private static final String RECOVER = "Recover";

    // Операции, повтор которых создает дубль: второе письмо, второе списание, вторая запись
    private static final Pattern NOT_IDEMPOTENT = Pattern.compile("^(send|create|charge|pay|transfer|insert|post|add)([A-Z].*)?$");

    @Override
    public String code() {
        return RuleCodes.CHECK_RETRYABLE_WITHOUT_RECOVER_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет @Retryable без @Recover и повтор операций, которые нельзя выполнять дважды";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            boolean hasRecover = type.getMethods().stream().anyMatch(method -> Annotations.has(method, RECOVER));
            for (MethodDeclaration method : type.getMethods()) {
                if (!Annotations.has(method, RETRYABLE)) {
                    continue;
                }
                String name = method.getNameAsString();
                if (NOT_IDEMPOTENT.matcher(name).matches()) {
                    violations.add(violation(sourceFile, method,
                            "@Retryable на методе '" + name + "': если первая попытка выполнилась, а ответ"
                                    + " потерялся, повтор создаст дубль (второе письмо, второе списание);"
                                    + " сделайте операцию идемпотентной - ключ идемпотентности, проверка перед записью"));
                } else if (!hasRecover) {
                    violations.add(violation(sourceFile, method,
                            "@Retryable на методе '" + name + "' без @Recover: когда попытки закончатся,"
                                    + " исключение уйдет вызывающему как есть; добавьте @Recover-метод, который"
                                    + " решает, что делать после последней неудачи"));
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
        return ErrorType.LOGICAL;
    }
}
