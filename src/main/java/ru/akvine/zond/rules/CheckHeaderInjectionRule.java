package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.BinaryExpr;
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
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class CheckHeaderInjectionRule extends AbstractRule {
    // response.setHeader(name, value), ResponseEntity.ok().header(name, value)
    private static final Set<String> HEADER_METHODS = Set.of("setHeader", "addHeader", "header");

    // Значение уже обработано: URLEncoder.encode(...), escape(...), sanitize(...)
    private static final Pattern SANITIZER = Pattern.compile(".*(encode|escape|sanitize|clean).*", Pattern.CASE_INSENSITIVE);

    // Константа - не пользовательский ввод
    private static final Pattern CONSTANT_NAME = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    private static final int HEADER_ARGUMENTS = 2;

    @Override
    public String code() {
        return RuleCodes.CHECK_HEADER_INJECTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет HTTP-заголовки, значение которых собирается конкатенацией с переменными";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!HEADER_METHODS.contains(call.getNameAsString()) || call.getArguments().size() < HEADER_ARGUMENTS) {
                continue;
            }

            findRawValue(call.getArgument(1)).ifPresent(raw -> violations.add(violation(sourceFile, call,
                    "В значение заголовка подставляется '" + raw + "' без экранирования: кавычка или перевод строки"
                            + " в нем сломает заголовок либо добавит новый (response splitting); для имени файла"
                            + " используйте ContentDisposition.builder(...).filename(...), остальное кодируйте")));
        }
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

    /**
     * @return первая переменная или вызов, вклеенные в значение заголовка как есть
     */
    private Optional<String> findRawValue(Expression value) {
        List<Expression> operands = new ArrayList<>();
        flatten(value, operands);

        // Значение без конкатенации (одна переменная, один вызов) не трогаем: откуда оно, по файлу не понять
        if (operands.size() < 2) {
            return Optional.empty();
        }
        return operands.stream()
                .filter(this::isRaw)
                .map(Expression::toString)
                .findFirst();
    }

    private void flatten(Expression expression, List<Expression> operands) {
        Expression value = Nodes.unwrap(expression);
        if (value.isBinaryExpr() && value.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
            flatten(value.asBinaryExpr().getLeft(), operands);
            flatten(value.asBinaryExpr().getRight(), operands);
        } else {
            operands.add(value);
        }
    }

    private boolean isRaw(Expression operand) {
        if (operand.isLiteralExpr()) {
            return false;
        }
        if (operand.isNameExpr()) {
            return !CONSTANT_NAME.matcher(operand.asNameExpr().getNameAsString()).matches();
        }
        if (operand.isFieldAccessExpr()) {
            return !CONSTANT_NAME.matcher(operand.asFieldAccessExpr().getNameAsString()).matches();
        }
        return !operand.isMethodCallExpr()
                || !SANITIZER.matcher(operand.asMethodCallExpr().getNameAsString()).matches();
    }
}
