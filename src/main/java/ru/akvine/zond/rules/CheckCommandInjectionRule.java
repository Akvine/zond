package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class CheckCommandInjectionRule extends AbstractRule {
    private static final String EXEC = "exec";
    private static final String GET_RUNTIME = "getRuntime";
    private static final String PROCESS_BUILDER = "ProcessBuilder";
    private static final String STRING = "String";
    private static final String FORMAT = "format";

    @Override
    public String code() {
        return RuleCodes.CHECK_COMMAND_INJECTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет запуск внешних команд, строка которых собирается из переменных";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();

        // Runtime.getRuntime().exec("ping " + host)
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            boolean isRuntimeExec = EXEC.equals(call.getNameAsString())
                    && call.getScope()
                    .filter(scope -> scope.isMethodCallExpr()
                            && GET_RUNTIME.equals(scope.asMethodCallExpr().getNameAsString()))
                    .isPresent();
            if (isRuntimeExec && call.getArguments().stream().anyMatch(this::isBuiltFromVariables)) {
                violations.add(report(sourceFile, call, "Runtime.exec(...)"));
            }
        }

        // new ProcessBuilder("sh", "-c", "ping " + host)
        for (ObjectCreationExpr creation : sourceFile.unit().findAll(ObjectCreationExpr.class)) {
            if (PROCESS_BUILDER.equals(creation.getType().getNameAsString())
                    && creation.getArguments().stream().anyMatch(this::isBuiltFromVariables)) {
                violations.add(report(sourceFile, creation, "new ProcessBuilder(...)"));
            }
        }

        violations.sort(Comparator.comparingInt(Violation::line));
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    private Violation report(SourceFile sourceFile, Node node, String source) {
        return violation(sourceFile, node,
                source + " со строкой команды, собранной из переменных: если в значение попадет ';' или '&&',"
                        + " выполнится чужая команда; передавайте программу и каждый аргумент отдельными"
                        + " элементами и не запускайте через оболочку");
    }

    // "ping " + host либо String.format("ping %s", host)
    private boolean isBuiltFromVariables(Expression argument) {
        Expression value = Nodes.unwrap(argument);
        if (value.isBinaryExpr() && value.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
            return StringLiterals.textOf(value).isEmpty();
        }
        return value.isMethodCallExpr() && MethodCalls.isCallOn(value.asMethodCallExpr(), STRING, FORMAT);
    }
}
