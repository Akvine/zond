package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.MethodCalls;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class CheckInsecureRandomRule extends AbstractRule {
    private static final String RANDOM = "Random";
    private static final String MATH = "Math";
    private static final String MATH_RANDOM = "random";
    private static final String THREAD_LOCAL_RANDOM = "ThreadLocalRandom";
    private static final String CURRENT = "current";

    // Типы не разрешаем: о том, что значение секретное, судим по имени переменной, метода или класса вокруг
    private static final Pattern SECRET_CONTEXT = Pattern.compile(
            ".*(token|password|passwd|secret|salt|nonce|otp|sessionid|apikey|captcha|verification).*",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String code() {
        return RuleCodes.CHECK_INSECURE_RANDOM_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет Random и Math.random() при генерации токенов, паролей и других секретов";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (Expression expression : sourceFile.unit().findAll(Expression.class)) {
            if (!isPredictableRandom(expression)) {
                continue;
            }
            findSecretContext(expression).ifPresent(context -> violations.add(violation(sourceFile, expression,
                    "'" + expression + "' при генерации секрета ('" + context + "'): последовательность обычного"
                            + " генератора предсказуема, по нескольким значениям восстанавливаются остальные;"
                            + " используйте SecureRandom")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    // new Random(...), Math.random(), ThreadLocalRandom.current()
    private boolean isPredictableRandom(Expression expression) {
        if (expression.isObjectCreationExpr()) {
            return RANDOM.equals(expression.asObjectCreationExpr().getType().getNameAsString());
        }
        return expression.isMethodCallExpr()
                && (MethodCalls.isCallOn(expression.asMethodCallExpr(), MATH, MATH_RANDOM)
                || MethodCalls.isCallOn(expression.asMethodCallExpr(), THREAD_LOCAL_RANDOM, CURRENT));
    }

    /**
     * @return имя переменной, метода или класса вокруг, которое говорит о секрете
     */
    private Optional<String> findSecretContext(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            String name = null;
            if (current instanceof VariableDeclarator variable) {
                name = variable.getNameAsString();
            } else if (current instanceof MethodDeclaration method) {
                name = method.getNameAsString();
            } else if (current instanceof TypeDeclaration<?> type) {
                name = type.getNameAsString();
            }
            if (name != null && SECRET_CONTEXT.matcher(name).matches()) {
                return Optional.of(name);
            }
            current = current.getParentNode().orElse(null);
        }
        return Optional.empty();
    }
}
