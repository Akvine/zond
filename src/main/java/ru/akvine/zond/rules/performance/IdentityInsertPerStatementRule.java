package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LiteralStringValueExpr;
import com.github.javaparser.ast.expr.SimpleName;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.TestPaths;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.JdbcBatches;
import ru.akvine.zond.rules.support.StringLiterals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code "SET IDENTITY_INSERT t ON\n" + insert + "SET IDENTITY_INSERT t OFF"}: переключатель вклеен в текст самого
 * запроса. Когда такой запрос уходит пакетом, SQL Server и Sybase выполняют включение и выключение на каждой
 * строке - три команды вместо одной. Переключатель действует на сеанс, поэтому его достаточно включить один раз
 * перед пакетом и выключить после.
 */
@Component
public class IdentityInsertPerStatementRule extends AbstractRule implements ProjectRule {
    private static final Pattern SWITCH_ON = Pattern.compile("(?is).*\\bset\\s+identity_insert\\b.*\\bon\\b.*");
    private static final Pattern SWITCH_OFF = Pattern.compile("(?is).*\\bset\\s+identity_insert\\b.*\\boff\\b.*");

    @Override
    public String code() {
        return RuleCodes.IDENTITY_INSERT_PER_STATEMENT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет SET IDENTITY_INSERT ON и OFF, вклеенные в текст одного запроса, в проекте,"
                + " который пишет в базу пакетами";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        if (!JdbcBatches.isUsedIn(sourceFiles)) {
            return List.of();
        }
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            if (TestPaths.isTest(sourceFile.path())) {
                continue;
            }
            Map<String, String> constants = findConstants(sourceFile);
            for (Expression expression : sourceFile.unit().findAll(Expression.class)) {
                // Только выражение целиком: его части дали бы ту же находку еще раз
                if (expression.getParentNode().filter(Expression.class::isInstance).isPresent()) {
                    continue;
                }
                List<String> texts = findTexts(expression, constants);
                if (texts.stream().anyMatch(text -> SWITCH_ON.matcher(text).matches())
                        && texts.stream().anyMatch(text -> SWITCH_OFF.matcher(text).matches())) {
                    violations.add(violation(sourceFile, expression,
                            "SET IDENTITY_INSERT ... ON и OFF вклеены в текст самого запроса: в пакете переключатель"
                                    + " выполняется дважды на каждой строке; он действует на сеанс - включите его"
                                    + " один раз перед пакетом и выключите после, на том же соединении"));
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
        return ErrorType.PERFORMANCE;
    }

    // private static final String PRE_QUERY = "set identity_insert %s on\n";
    private Map<String, String> findConstants(SourceFile sourceFile) {
        Map<String, String> constants = new HashMap<>();
        for (FieldDeclaration field : sourceFile.unit().findAll(FieldDeclaration.class)) {
            if (!field.isStatic() || !field.isFinal()) {
                continue;
            }
            for (VariableDeclarator variable : field.getVariables()) {
                variable.getInitializer()
                        .flatMap(StringLiterals::textOf)
                        .ifPresent(text -> constants.put(variable.getNameAsString(), text));
            }
        }
        return constants;
    }

    // Литералы выражения и константы, на которые оно ссылается
    private List<String> findTexts(Expression expression, Map<String, String> constants) {
        List<String> texts = new ArrayList<>();
        for (LiteralStringValueExpr literal : expression.findAll(LiteralStringValueExpr.class)) {
            if (literal.isStringLiteralExpr() || literal.isTextBlockLiteralExpr()) {
                texts.add(literal.getValue());
            }
        }
        if (constants.isEmpty()) {
            return texts;
        }
        for (SimpleName name : expression.findAll(SimpleName.class)) {
            String text = constants.get(name.getIdentifier());
            boolean isOwnDeclaration = name.getParentNode().filter(VariableDeclarator.class::isInstance).isPresent();
            if (text != null && !isOwnDeclaration) {
                texts.add(text);
            }
        }
        return texts;
    }
}
