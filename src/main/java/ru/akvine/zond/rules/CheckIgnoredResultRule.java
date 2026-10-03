package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckIgnoredResultRule extends AbstractRule {
    // Методы этих классов объект не меняют, а возвращают новый
    private static final Set<String> IMMUTABLE_TYPES = Set.of(
            "String", "BigDecimal", "BigInteger", "LocalDate", "LocalDateTime", "LocalTime", "ZonedDateTime",
            "OffsetDateTime", "Instant", "Duration", "Period");

    // Вызываются ради побочного эффекта или ради проверки с исключением
    private static final Set<String> SIDE_EFFECT_METHODS = Set.of("getChars", "wait", "notify", "notifyAll");
    private static final String EXACT_SUFFIX = "Exact";

    @Override
    public String code() {
        return RuleCodes.CHECK_IGNORED_RESULT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет вызовы методов неизменяемых объектов, результат которых не используется";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ExpressionStmt statement : sourceFile.unit().findAll(ExpressionStmt.class)) {
            // item -> text.equals(item): тело лямбды-выражения тоже хранится как оператор, но его результат нужен
            boolean isLambdaBody = statement.getParentNode().filter(parent -> parent instanceof LambdaExpr).isPresent();
            // case A -> text.trim(); в switch-выражении - это значение ветки, а не отдельный оператор
            boolean isSwitchValue = statement.getParentNode()
                    .filter(parent -> parent instanceof SwitchEntry)
                    .flatMap(Node::getParentNode)
                    .filter(parent -> parent instanceof SwitchExpr)
                    .isPresent();
            if (isLambdaBody || isSwitchValue || !statement.getExpression().isMethodCallExpr()) {
                continue;
            }

            MethodCallExpr call = statement.getExpression().asMethodCallExpr();
            String method = call.getNameAsString();
            if (call.getScope().isEmpty() || SIDE_EFFECT_METHODS.contains(method) || method.endsWith(EXACT_SUFFIX)) {
                continue;
            }

            // Только вызов прямо на переменной: тип результата цепочки по файлу не определить
            Optional<String> type = LocalTypes.typeOf(call.getScope().get()).filter(IMMUTABLE_TYPES::contains);
            type.ifPresent(typeName -> violations.add(violation(sourceFile, call,
                    "Результат '" + call + "' не используется: " + typeName + " неизменяем, метод " + method
                            + " возвращает новый объект, а исходный остается прежним")));
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
}
