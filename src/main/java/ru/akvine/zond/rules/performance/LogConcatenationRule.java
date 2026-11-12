package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Loggers;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.StringLiterals;

import java.util.List;
import java.util.regex.Pattern;

@Component
public class LogConcatenationRule extends AbstractRule {
    private static final Pattern LEVEL_CHECK = Pattern.compile("is(Trace|Debug|Info|Warn|Error|Fatal)Enabled|isLoggable");

    private static final String STRING = "String";
    private static final String FORMAT = "format";

    @Override
    public String code() {
        return RuleCodes.LOG_CONCATENATION_RULE_CODE;
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
                .filter(call -> !isUnderLevelCheck(call))
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

    // if (logger.isDebugEnabled()) { logger.debug("Rejected [" + expression + "]"); } - строка собирается только
    // при включенном уровне, а у commons-logging подстановок нет вовсе
    private boolean isUnderLevelCheck(MethodCallExpr call) {
        Node current = call.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof IfStmt check && Nodes.isInside(call, check.getThenStmt())
                    && check.getCondition().findFirst(MethodCallExpr.class,
                    asked -> LEVEL_CHECK.matcher(asked.getNameAsString()).matches()).isPresent()) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }
}
