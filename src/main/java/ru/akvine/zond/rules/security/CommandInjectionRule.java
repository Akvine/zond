package ru.akvine.zond.rules.security;

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
import ru.akvine.zond.rules.AbstractTaintRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.StringLiterals;
import ru.akvine.zond.rules.support.Taint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
public class CommandInjectionRule extends AbstractTaintRule {
    private static final String EXEC = "exec";
    private static final String GET_RUNTIME = "getRuntime";
    private static final String PROCESS_BUILDER = "ProcessBuilder";
    private static final String COMMAND = "command";
    private static final String RUNTIME = "Runtime";
    private static final String STRING = "String";
    private static final String FORMAT = "format";

    @Override
    public String code() {
        return RuleCodes.COMMAND_INJECTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет запуск внешних команд, строка которых собирается из переменных";
    }

    @Override
    protected List<Violation> check(SourceFile sourceFile, Taint taint) {
        List<Violation> violations = new ArrayList<>();

        // Runtime.getRuntime().exec("ping " + host), r.exec(args, env), processBuilder.command(arguments)
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            boolean isRuntimeExec = EXEC.equals(call.getNameAsString()) && call.getScope().filter(this::isRuntime).isPresent();
            boolean isCommand = COMMAND.equals(call.getNameAsString()) && !call.getArguments().isEmpty()
                    && call.getScope().filter(this::isProcessBuilder).isPresent();
            if ((isRuntimeExec || isCommand) && isDangerous(call.getArguments(), taint)) {
                String sink = isRuntimeExec ? "Runtime.exec(...)" : "ProcessBuilder.command(...)";
                violations.add(report(sourceFile, call, sink + describeOrigin(call.getArguments(), taint)));
            }
        }

        // new ProcessBuilder("sh", "-c", "ping " + host)
        for (ObjectCreationExpr creation : sourceFile.unit().findAll(ObjectCreationExpr.class)) {
            if (PROCESS_BUILDER.equals(creation.getType().getNameAsString())
                    && isDangerous(creation.getArguments(), taint)) {
                violations.add(report(sourceFile, creation,
                        "new ProcessBuilder(...)" + describeOrigin(creation.getArguments(), taint)));
            }
        }

        violations.sort(Comparator.comparingInt(Violation::line));
        return violations;
    }

    // Данные попадают в команда ОС: экранирование для другого места их не обезвреживает
    @Override
    protected Taint.Sink sink() {
        return Taint.Sink.COMMAND;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    // Данные клиента в команде - уже не теоретическая, а прямая уязвимость: говорим об этом в сообщении
    private String describeOrigin(List<Expression> arguments, Taint taint) {
        return arguments.stream()
                .map(taint::findSource)
                .flatMap(Optional::stream)
                .findFirst()
                .map(source -> " (" + source.describe() + ")")
                .orElse("");
    }

    private Violation report(SourceFile sourceFile, Node node, String source) {
        return violation(sourceFile, node,
                source + " со строкой команды, собранной из переменных: если в значение попадет ';' или '&&',"
                        + " выполнится чужая команда; передавайте программу и каждый аргумент отдельными"
                        + " элементами и не запускайте через оболочку");
    }

    // Строка команды собрана из переменных либо в команду, ее аргументы или окружение попадают данные клиента
    private boolean isDangerous(List<Expression> arguments, Taint taint) {
        return arguments.stream().anyMatch(this::isBuiltFromVariables)
                || arguments.stream().anyMatch(argument -> taint.findSource(argument).isPresent());
    }

    // Runtime.getRuntime() либо переменная, в которую его положили
    private boolean isRuntime(Expression scope) {
        Expression value = Nodes.unwrap(scope);
        if (value.isMethodCallExpr()) {
            return GET_RUNTIME.equals(value.asMethodCallExpr().getNameAsString());
        }
        return LocalTypes.typeOf(value).filter(RUNTIME::equals).isPresent()
                || LocalTypes.findInitializer(value).filter(this::isRuntime).isPresent();
    }

    private boolean isProcessBuilder(Expression scope) {
        Expression value = Nodes.unwrap(scope);
        return LocalTypes.typeOf(value).filter(PROCESS_BUILDER::equals).isPresent();
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
