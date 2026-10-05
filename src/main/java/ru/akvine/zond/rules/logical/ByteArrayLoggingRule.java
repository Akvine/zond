package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loggers;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class ByteArrayLoggingRule extends AbstractRule {
    private static final Set<String> RAW_ARRAY_TYPES = Set.of("byte[]", "char[]", "Byte[]");
    private static final String STRING = "String";

    // Методы, которые заведомо возвращают массив байтов: тип результата по коду виден не всегда
    private static final Set<String> BYTE_SOURCES = Set.of("getBytes", "readAllBytes", "toByteArray", "getContentAsByteArray");

    // Arrays.toString(bytes), Base64.getEncoder().encodeToString(bytes), HexFormat.of().formatHex(bytes),
    // String.valueOf(chars): превращают массив в строку целиком
    private static final Set<String> CONTENT_METHODS = Set.of(
            "toString", "encodeToString", "encodeHexString", "formatHex", "valueOf", "copyValueOf", "printHexBinary");

    @Override
    public String code() {
        return RuleCodes.BYTE_ARRAY_LOGGING_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет запись массивов байтов в лог: самого массива либо всего его содержимого";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!Loggers.isLogCall(call) || TestClasses.isInside(call)) {
                continue;
            }
            call.getArguments().stream()
                    .map(this::describeProblem)
                    .flatMap(Optional::stream)
                    .findFirst()
                    .ifPresent(problem -> violations.add(violation(sourceFile, call, problem)));
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

    private Optional<String> describeProblem(Expression argument) {
        Expression value = Nodes.unwrap(argument);
        // "body: " + bytes
        if (value.isBinaryExpr() && value.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
            return describeProblem(value.asBinaryExpr().getLeft())
                    .or(() -> describeProblem(value.asBinaryExpr().getRight()));
        }
        if (isRawArray(value)) {
            return Optional.of("В лог передается массив '" + value + "': вместо содержимого запишется адрес"
                    + " объекта вида [B@1b2c3d, по которому ничего не понять; пишите длину массива либо то,"
                    + " что из него действительно нужно");
        }
        if (convertsWholeArray(value)) {
            return Optional.of("В лог пишется все содержимое массива байтов ('" + value + "'): файл или тело"
                    + " запроса целиком раздувает лог и уносит в него чужие данные; пишите длину, тип"
                    + " содержимого либо контрольную сумму");
        }
        return Optional.empty();
    }

    private boolean isRawArray(Expression value) {
        if (LocalTypes.typeOf(value).filter(RAW_ARRAY_TYPES::contains).isPresent()) {
            return true;
        }
        return value.isMethodCallExpr() && BYTE_SOURCES.contains(value.asMethodCallExpr().getNameAsString());
    }

    // new String(bytes, UTF_8) либо метод, который переводит массив в строку
    private boolean convertsWholeArray(Expression value) {
        if (value.isObjectCreationExpr()) {
            return STRING.equals(value.asObjectCreationExpr().getType().getNameAsString())
                    && value.asObjectCreationExpr().getArguments().stream().findFirst().filter(this::isRawArray).isPresent();
        }
        return value.isMethodCallExpr()
                && CONTENT_METHODS.contains(value.asMethodCallExpr().getNameAsString())
                && value.asMethodCallExpr().getArguments().stream().anyMatch(this::isRawArray);
    }
}
