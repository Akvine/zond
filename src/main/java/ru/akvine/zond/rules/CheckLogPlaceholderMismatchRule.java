package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CheckLogPlaceholderMismatchRule extends AbstractRule {
    private static final Pattern PLACEHOLDER = Pattern.compile("(?<!\\\\)\\{}");
    private static final String ARRAY_SUFFIX = "[]";

    // Типы не разрешаем: исключение в конце списка аргументов узнаем по имени
    private static final Pattern EXCEPTION_NAME =
            Pattern.compile("^(e|ex|exc|t|th|cause|error|throwable)$|.*(exception|error|throwable)$", Pattern.CASE_INSENSITIVE);

    @Override
    public String code() {
        return RuleCodes.CHECK_LOG_PLACEHOLDER_MISMATCH_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет вызовы логгера, где число {} не совпадает с числом аргументов";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            // Первый аргумент должен быть сообщением, целиком известным из кода
            if (!Loggers.isLogCall(call) || call.getArguments().isEmpty()) {
                continue;
            }
            Optional<String> message = StringLiterals.textOf(call.getArgument(0));
            if (message.isEmpty()) {
                continue;
            }

            int placeholders = count(message.get());
            int arguments = call.getArguments().size() - 1;

            // Исключение последним аргументом в подстановке не участвует: логгер выводит его стек
            Expression last = call.getArgument(call.getArguments().size() - 1);
            if (arguments > placeholders && isException(last, call)) {
                arguments--;
            }

            // Единственный аргумент-массив раскрывается в несколько значений: log.info("{} {}", values)
            boolean isArray = call.getArguments().size() == 2
                    && LocalTypes.typeOf(call.getArgument(1)).filter(type -> type.endsWith(ARRAY_SUFFIX)).isPresent();
            if (placeholders != arguments && !isArray) {
                violations.add(violation(sourceFile, call,
                        "В сообщении " + placeholders + " подстановок {}, а аргументов " + arguments + ": "
                                + (placeholders > arguments
                                ? "лишние {} так и останутся в тексте"
                                : "лишние аргументы в лог не попадут")
                                + "; приведите их в соответствие"));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private int count(String message) {
        Matcher matcher = PLACEHOLDER.matcher(message);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    // Переменная блока catch либо имя, похожее на исключение
    private boolean isException(Expression argument, MethodCallExpr call) {
        if (!argument.isNameExpr()) {
            return false;
        }
        String name = argument.asNameExpr().getNameAsString();
        Node current = call.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof CatchClause clause && clause.getParameter().getNameAsString().equals(name)) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return EXCEPTION_NAME.matcher(name).matches();
    }
}
