package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Secrets;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class CheckHardcodedCredentialsRule extends AbstractRule {
    private static final String SECRET_WORDS =
            "password|passwd|pwd|secret|token|api_?key|private_?key|access_?key|credential";

    // setPassword("..."), withToken("..."), password("...")
    private static final Pattern SECRET_SETTER =
            Pattern.compile("^(set|with)?(" + SECRET_WORDS + ")$", Pattern.CASE_INSENSITIVE);

    @Override
    public String code() {
        return RuleCodes.CHECK_HARDCODED_CREDENTIALS_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет пароли, токены и ключи, записанные строкой прямо в коде";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Optional<Violation>> violations = new ArrayList<>();

        for (VariableDeclarator variable : sourceFile.unit().findAll(VariableDeclarator.class)) {
            variable.getInitializer()
                    .filter(value -> isSecret(variable.getNameAsString(), value))
                    .ifPresent(value -> violations.add(report(sourceFile, variable, variable.getNameAsString())));
        }

        for (AssignExpr assign : sourceFile.unit().findAll(AssignExpr.class)) {
            targetName(assign.getTarget())
                    .filter(name -> isSecret(name, assign.getValue()))
                    .ifPresent(name -> violations.add(report(sourceFile, assign, name)));
        }

        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (SECRET_SETTER.matcher(call.getNameAsString()).matches()
                    && call.getArguments().size() == 1
                    && isSecretValue(call.getArgument(0), call.getNameAsString())) {
                violations.add(report(sourceFile, call, call.getNameAsString() + "(...)"));
            }
        }

        return violations.stream().flatMap(Optional::stream).toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    // В тестах учетные данные заведомо ненастоящие
    private Optional<Violation> report(SourceFile sourceFile, Node node, String name) {
        if (TestClasses.isInside(node)) {
            return Optional.empty();
        }
        return Optional.of(violation(sourceFile, node,
                "Секрет '" + name + "' записан в коде строкой: он попадет в репозиторий и сборку;"
                        + " вынесите его в настройки окружения или хранилище секретов"));
    }

    private boolean isSecret(String name, Expression value) {
        // TOKEN_HEADER, REVOKE_TOKEN_BUTTON_TEXT, tokenService: слово о секрете в имени есть, но самого секрета нет
        return Secrets.isSecretName(name) && isSecretValue(value, name);
    }

    private boolean isSecretValue(Expression expression, String name) {
        Expression value = Nodes.unwrap(expression);
        if (!value.isStringLiteralExpr()) {
            return false;
        }

        // Подпись, сообщение, адрес, название параметра или подстановка из настроек - не секрет
        String text = value.asStringLiteralExpr().asString();
        if (!Secrets.isSecretValue(text)) {
            return false;
        }

        // PASSWORD_KEY = "password": значение повторяет имя, это ключ, а не сам секрет
        String normalizedValue = normalize(text);
        return normalizedValue.isEmpty() || !normalize(name).contains(normalizedValue);
    }

    private String normalize(String text) {
        return text.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private Optional<String> targetName(Expression target) {
        if (target.isNameExpr()) {
            return Optional.of(target.asNameExpr().getNameAsString());
        }
        if (target.isFieldAccessExpr()) {
            return Optional.of(target.asFieldAccessExpr().getNameAsString());
        }
        return Optional.empty();
    }
}
