package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ProtectedProperties;
import ru.akvine.zond.rules.support.Secrets;
import ru.akvine.zond.rules.support.StringLiterals;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.List;
import java.util.Optional;

@Component
public class CheckSecretComparisonRule extends AbstractRule implements ProjectRule {
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
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProtectedProperties properties = ProtectedProperties.of(sourceFiles);
        return sourceFiles.stream().flatMap(sourceFile -> check(sourceFile, properties).stream()).toList();
    }

    private List<Violation> check(SourceFile sourceFile, ProtectedProperties properties) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> EQUALS.equals(call.getNameAsString()) && call.getArguments().size() == 1)
                .filter(call -> call.getScope().isPresent()
                        && comparesSecrets(call.getScope().get(), call.getArgument(0), properties))
                .filter(call -> !TestClasses.isInside(call))
                .map(call -> violation(sourceFile, call,
                        "'" + call + "': equals останавливается на первом несовпавшем символе, и по времени ответа"
                                + " секрет можно подобрать по частям; сравнивайте через MessageDigest.isEqual(...),"
                                + " а пароли - через PasswordEncoder.matches(...)"))
                .toList();
    }

    // О том, что сравнивается секрет, говорит только имя переменной
    @Override
    public Confidence confidence() {
        return Confidence.SUSPICION;
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
    private boolean comparesSecrets(Expression left, Expression right, ProtectedProperties properties) {
        if (Nodes.unwrap(left).isLiteralExpr() || Nodes.unwrap(right).isLiteralExpr()) {
            return false;
        }
        return isSecret(left, properties) || isSecret(right, properties);
    }

    // Хеш и шифртекст подбирать по времени ответа бессмысленно; константа с названием вида значения
    // (SECRET = "Secret") секретом не является
    private boolean isSecret(Expression expression, ProtectedProperties properties) {
        if (!Secrets.isSecretName(nameOf(expression)) || properties.isProtectedValue(expression)) {
            return false;
        }
        Optional<String> constant = Nodes.unwrap(expression).isNameExpr()
                ? LocalTypes.findField(expression, nameOf(expression))
                        .flatMap(VariableDeclarator::getInitializer)
                        .flatMap(StringLiterals::textOf)
                : Optional.empty();
        return constant.isEmpty() || Secrets.isSecretValue(constant.get());
    }

    // user.getPassword() -> password: о секрете говорит свойство, а не слово get
    private String nameOf(Expression expression) {
        String name = MethodCalls.receiverName(expression);
        return name.startsWith("get") && name.length() > 3 ? name.substring(3) : name;
    }
}
