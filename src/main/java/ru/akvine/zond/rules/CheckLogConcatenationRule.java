package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;

@Component
public class CheckLogConcatenationRule extends AbstractRule {
    private static final String STRING = "String";
    private static final String FORMAT = "format";

    @Override
    public String code() {
        return RuleCodes.CHECK_LOG_CONCATENATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет сообщения логгера, собранные конкатенацией или String.format";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(Loggers::isLogCall)
                .filter(call -> call.getArguments().stream().anyMatch(this::isBuiltEagerly))
                .map(call -> violation(sourceFile, call,
                        "Сообщение для " + call.getScope().get() + "." + call.getNameAsString() + "(...) собирается"
                                + " до вызова: строка формируется, даже когда этот уровень логирования выключен;"
                                + " используйте подстановку: log.info(\"id={}\", id)"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    // "id=" + id либо String.format("id=%s", id); конкатенация одних литералов склеивается компилятором
    private boolean isBuiltEagerly(Expression argument) {
        Expression value = Nodes.unwrap(argument);
        if (value.isBinaryExpr() && value.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
            return StringLiterals.textOf(value).isEmpty();
        }
        return value.isMethodCallExpr() && MethodCalls.isCallOn(value.asMethodCallExpr(), STRING, FORMAT);
    }
}
