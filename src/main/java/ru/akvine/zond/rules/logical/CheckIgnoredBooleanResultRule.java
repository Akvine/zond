package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckIgnoredBooleanResultRule extends AbstractRule {
    private static final String FILE = "File";

    // Методы File сообщают о неудаче не исключением, а значением false
    private static final Set<String> FILE_METHODS = Set.of(
            "delete", "mkdir", "mkdirs", "createNewFile", "renameTo", "setReadable", "setWritable",
            "setExecutable", "setLastModified");

    // Попытка, которая может не удаться: без проверки результата код продолжит работу как после успеха
    private static final Set<String> ATTEMPT_METHODS = Set.of("tryLock", "tryAcquire", "awaitTermination");

    private static final String OFFER = "offer";
    private static final Set<String> QUEUE_SUFFIXES = Set.of("Queue", "Deque");

    @Override
    public String code() {
        return RuleCodes.CHECK_IGNORED_BOOLEAN_RESULT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет вызовы, которые сообщают о неудаче значением false, а результат не проверяется";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ExpressionStmt statement : sourceFile.unit().findAll(ExpressionStmt.class)) {
            // Тело лямбды-выражения хранится как оператор, но его результат возвращается
            boolean isLambdaBody = statement.getParentNode().filter(parent -> parent instanceof LambdaExpr).isPresent();
            if (isLambdaBody || !statement.getExpression().isMethodCallExpr()) {
                continue;
            }

            MethodCallExpr call = statement.getExpression().asMethodCallExpr();
            describeProblem(call).ifPresent(problem -> violations.add(violation(sourceFile, call,
                    "Результат '" + call + "' не проверяется: " + problem)));
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

    private Optional<String> describeProblem(MethodCallExpr call) {
        if (call.getScope().isEmpty()) {
            return Optional.empty();
        }

        Expression scope = call.getScope().get();
        String method = call.getNameAsString();
        Optional<String> type = LocalTypes.typeOf(scope);

        if (FILE_METHODS.contains(method) && type.filter(FILE::equals).isPresent()) {
            return Optional.of("при неудаче метод не бросает исключение, а возвращает false, и ошибка останется"
                    + " незамеченной; проверьте результат либо используйте методы Files, которые бросают IOException");
        }
        if (ATTEMPT_METHODS.contains(method)) {
            return Optional.of("если попытка не удалась, код ниже все равно выполнится так, будто она удалась;"
                    + " проверьте результат");
        }
        if (OFFER.equals(method) && type.filter(this::isQueue).isPresent()) {
            return Optional.of("если очередь заполнена, элемент молча теряется; проверьте результат"
                    + " либо используйте put / add");
        }
        return Optional.empty();
    }

    private boolean isQueue(String type) {
        return QUEUE_SUFFIXES.stream().anyMatch(type::endsWith);
    }
}
