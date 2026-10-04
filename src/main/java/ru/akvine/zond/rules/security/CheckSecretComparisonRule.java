package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Secrets;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.List;

@Component
public class CheckSecretComparisonRule extends AbstractRule {
    private static final String EQUALS = "equals";

    @Override
    public String code() {
        return RuleCodes.CHECK_SECRET_COMPARISON_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет сравнение паролей, токенов и подписей через equals";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> EQUALS.equals(call.getNameAsString()) && call.getArguments().size() == 1)
                .filter(call -> call.getScope().isPresent() && comparesSecrets(call.getScope().get(), call.getArgument(0)))
                .filter(call -> !TestClasses.isInside(call))
                .map(call -> violation(sourceFile, call,
                        "'" + call + "': equals останавливается на первом несовпавшем символе, и по времени ответа"
                                + " секрет можно подобрать по частям; сравнивайте через MessageDigest.isEqual(...),"
                                + " а пароли - через PasswordEncoder.matches(...)"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    // Сравнение с литералом ("".equals(token)) - проверка на пустоту, а не сверка секрета
    private boolean comparesSecrets(Expression left, Expression right) {
        if (Nodes.unwrap(left).isLiteralExpr() || Nodes.unwrap(right).isLiteralExpr()) {
            return false;
        }
        return Secrets.isSecretName(nameOf(left)) || Secrets.isSecretName(nameOf(right));
    }

    // user.getPassword() -> password: о секрете говорит свойство, а не слово get
    private String nameOf(Expression expression) {
        String name = MethodCalls.receiverName(expression);
        return name.startsWith("get") && name.length() > 3 ? name.substring(3) : name;
    }
}
