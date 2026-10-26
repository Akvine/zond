package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractTaintRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.ConstantValues;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.StringLiterals;
import ru.akvine.zond.rules.support.Taint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CommandInjectionRule extends AbstractTaintRule {
    private static final String EXEC = "exec";
    private static final String GET_RUNTIME = "getRuntime";
    private static final String PROCESS_BUILDER = "ProcessBuilder";
    private static final String COMMAND = "command";
    private static final String VALUE = "Value";
    // Через сколько локальных переменных прослеживается часть команды
    private static final int MAX_ORIGIN_DEPTH = 3;
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

    // В команду, ее аргументы или окружение попадают данные клиента либо строка команды собрана из значений,
    // которые могут прийти снаружи: параметров метода и изменяемых полей
    private boolean isDangerous(List<Expression> arguments, Taint taint) {
        return arguments.stream().anyMatch(argument -> taint.findSource(argument).isPresent())
                || arguments.stream().anyMatch(argument -> isBuiltFromVariables(argument)
                && mayComeFromOutside(argument, 0, taint));
    }

    /**
     * cmd + bar, где cmd = getInsecureOSCommandString() и bar = "safe": все части - константы, значения настроек
     * (@Value) и то, что метод вычислил сам. Такую команду никто снаружи не задает. Параметр метода (его передают
     * извне) и поле без final - задают. Как и при прослеживании данных, учитываются только присваивания, которые
     * доходят до места, известные условия и то, какие аргументы свой метод возвращает
     */
    private boolean mayComeFromOutside(Expression expression, int depth, Taint taint) {
        if (depth > MAX_ORIGIN_DEPTH) {
            return true;
        }
        Expression value = Nodes.unwrap(expression);
        if (value.isNameExpr()) {
            return nameMayComeFromOutside(value.asNameExpr(), depth, taint);
        }
        // (7 * 42) - num > 200 ? "safe" : param - вторая ветка не выполнится
        if (value.isConditionalExpr()) {
            ConditionalExpr branch = value.asConditionalExpr();
            Optional<Boolean> known = ConstantValues.condition(branch.getCondition());
            if (known.isPresent()) {
                return mayComeFromOutside(known.get() ? branch.getThenExpr() : branch.getElseExpr(), depth, taint);
            }
        }
        // new Test().doSomething(request, param): до результата доходят только эти аргументы
        if (value.isMethodCallExpr()) {
            MethodCallExpr call = value.asMethodCallExpr();
            Optional<Set<Integer>> returned = taint.returnedArguments(call);
            if (returned.isPresent()) {
                return returned.get().stream().anyMatch(index -> mayComeFromOutside(call.getArgument(index), depth, taint));
            }
        }
        return value.getChildNodes().stream()
                .filter(Expression.class::isInstance)
                .anyMatch(child -> mayComeFromOutside((Expression) child, depth, taint));
    }

    private boolean nameMayComeFromOutside(NameExpr name, int depth, Taint taint) {
        Optional<Node> declaration = LocalTypes.findDeclaration(name);
        if (declaration.isEmpty()) {
            return false;
        }
        if (declaration.get() instanceof Parameter) {
            return true;
        }
        if (!(declaration.get() instanceof VariableDeclarator variable)) {
            return false;
        }
        Optional<FieldDeclaration> field = variable.findAncestor(FieldDeclaration.class);
        if (field.isPresent()) {
            return !field.get().isFinal() && !Annotations.has(field.get(), VALUE);
        }
        Optional<Set<Node>> reaching = taint.definitionsReaching(name);
        if (reaching.isPresent()) {
            return reaching.get().stream().anyMatch(definition -> definitionMayComeFromOutside(definition, depth, taint));
        }
        return variable.getInitializer().filter(value -> mayComeFromOutside(value, depth + 1, taint)).isPresent()
                || assignedValues(variable).stream().anyMatch(value -> mayComeFromOutside(value, depth + 1, taint));
    }

    // Параметр, присваивание, объявление с начальным значением, переменная цикла for-each
    private boolean definitionMayComeFromOutside(Node definition, int depth, Taint taint) {
        if (definition instanceof Parameter) {
            return true;
        }
        if (definition instanceof AssignExpr assignment) {
            return mayComeFromOutside(assignment.getValue(), depth + 1, taint);
        }
        if (definition instanceof VariableDeclarator variable) {
            Optional<Node> loop = variable.getParentNode().flatMap(Node::getParentNode).filter(ForEachStmt.class::isInstance);
            if (loop.isPresent()) {
                return mayComeFromOutside(((ForEachStmt) loop.get()).getIterable(), depth + 1, taint);
            }
            return variable.getInitializer().filter(value -> mayComeFromOutside(value, depth + 1, taint)).isPresent();
        }
        return true;
    }

    private List<Expression> assignedValues(VariableDeclarator variable) {
        return Nodes.enclosingCallable(variable).stream()
                .flatMap(callable -> callable.findAll(AssignExpr.class).stream())
                .filter(assignment -> assignment.getTarget().isNameExpr()
                        && LocalTypes.findDeclaration(assignment.getTarget()).filter(found -> found == variable).isPresent())
                .map(AssignExpr::getValue)
                .toList();
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
