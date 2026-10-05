package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class SeededSecureRandomRule extends AbstractRule {
    private static final String SECURE_RANDOM = "SecureRandom";
    private static final String SET_SEED = "setSeed";

    @Override
    public String code() {
        return RuleCodes.SEEDED_SECURE_RANDOM_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет SecureRandom, которому seed задают вручную";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();

        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (SET_SEED.equals(call.getNameAsString())
                    && call.getScope().filter(this::isSecureRandom).isPresent()) {
                violations.add(report(sourceFile, call, call.getScope().get() + ".setSeed(...)"));
            }
        }

        // new SecureRandom(seed)
        for (ObjectCreationExpr creation : sourceFile.unit().findAll(ObjectCreationExpr.class)) {
            if (SECURE_RANDOM.equals(creation.getType().getNameAsString()) && !creation.getArguments().isEmpty()) {
                violations.add(report(sourceFile, creation, "new SecureRandom(seed)"));
            }
        }

        violations.sort(Comparator.comparingInt(Violation::line));
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

    private Violation report(SourceFile sourceFile, Node node, String source) {
        return violation(sourceFile, node,
                "'" + source + "': с заданным seed последовательность SecureRandom становится предсказуемой - для"
                        + " воспроизводимых тестовых данных это нормально, но токены, пароли и ключи так"
                        + " генерировать нельзя; для них используйте SecureRandom без seed");
    }

    // Переменная типа SecureRandom либо SecureRandom.getInstance(...).setSeed(...)
    private boolean isSecureRandom(Expression scope) {
        Expression value = Nodes.unwrap(scope);
        if (value.isMethodCallExpr()) {
            return value.asMethodCallExpr().getScope()
                    .filter(type -> MethodCalls.isType(type, SECURE_RANDOM))
                    .isPresent();
        }
        return LocalTypes.typeOf(value).filter(SECURE_RANDOM::equals).isPresent();
    }
}
