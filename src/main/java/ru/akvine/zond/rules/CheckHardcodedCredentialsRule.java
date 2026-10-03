package ru.akvine.zond.rules;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class CheckHardcodedCredentialsRule extends AbstractRule {
    private static final String SECRET_WORDS =
            "password|passwd|pwd|secret|token|api_?key|private_?key|access_?key|credential";

    private static final Pattern SECRET_NAME =
            Pattern.compile(".*(" + SECRET_WORDS + ").*", Pattern.CASE_INSENSITIVE);

    // TOKEN_HEADER = "X-Auth-Token", PASSWORD_PARAM = "pass", INVALID_TOKEN_CODE = "E42":
    // в таких константах лежит не секрет, а его название или что-то, что к нему относится
    private static final Pattern NOT_SECRET_NAME = Pattern.compile(
            ".*(header|param|parameter|field|name|prefix|suffix|path|url|uri|property|attribute|claim|type|label"
                    + "|message|pattern|regex|format|column|cookie|code|error|words|id)$",
            Pattern.CASE_INSENSITIVE);

    // setPassword("..."), withToken("..."), password("...")
    private static final Pattern SECRET_SETTER =
            Pattern.compile("^(set|with)?(" + SECRET_WORDS + ")$", Pattern.CASE_INSENSITIVE);

    // ${db.password}, #{...}: значение подставляется из настроек
    private static final Pattern PLACEHOLDER_VALUE = Pattern.compile("^[$#]\\{.*");

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
        return SECRET_NAME.matcher(name).matches()
                && !NOT_SECRET_NAME.matcher(name).matches()
                && isSecretValue(value, name);
    }

    private boolean isSecretValue(Expression expression, String name) {
        Expression value = Nodes.unwrap(expression);
        if (!value.isStringLiteralExpr()) {
            return false;
        }

        String text = value.asStringLiteralExpr().asString();
        if (text.isBlank() || PLACEHOLDER_VALUE.matcher(text).matches()) {
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
