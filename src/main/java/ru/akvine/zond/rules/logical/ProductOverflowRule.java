package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ForStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.TestPaths;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.ConstantValues;
import ru.akvine.zond.rules.support.DataValues;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loops;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code long result = 1; for (long i = 2; i <= n; i++) result *= i;}: в long помещается только 20!, в int - 12!.
 * Дальше произведение переполняется, и Java об этом не сообщает: результат просто становится неверным - сначала
 * отрицательным, затем нулем. То же с возведением в степень и любым произведением, которое копится в цикле.
 * Math.multiplyExact бросает исключение при переполнении, BigInteger не переполняется вовсе.
 */
@Component
public class ProductOverflowRule extends AbstractRule {
    private static final Set<String> INTEGER_TYPES = Set.of("int", "long", "Integer", "Long");
    private static final String REDUCE = "reduce";
    private static final int PAIR = 2;
    // Проверка переполнения уже есть: result > Long.MAX_VALUE / i, Math.multiplyExact(...)
    private static final List<String> OVERFLOW_CHECKS = List.of("MAX_VALUE", "multiplyExact", "multiplyHigh");
    private static final String HASH = "hash";
    // delay *= factor: шагов столько, сколько попыток, и значение ограничивают отдельно
    private static final List<String> BACKOFF_NAMES =
            List.of("delay", "timeout", "interval", "backoff", "wait", "sleep", "period", "pause");
    // 20! - последний факториал, который помещается в long
    private static final long SAFE_STEPS = 12;

    @Override
    public String code() {
        return RuleCodes.PRODUCT_OVERFLOW_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет произведение, которое накапливается в int или long в цикле, рекурсии или"
                + " стриме без проверки переполнения: факториал, степень";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        if (TestPaths.isTest(sourceFile.path())) {
            return violations;
        }

        for (AssignExpr assignment : sourceFile.unit().findAll(AssignExpr.class)) {
            checkLoop(sourceFile, assignment).ifPresent(violations::add);
        }
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            checkReduce(sourceFile, call).ifPresent(violations::add);
        }
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            checkRecursion(sourceFile, method).ifPresent(violations::add);
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

    // result *= i; result = result * i
    private Optional<Violation> checkLoop(SourceFile sourceFile, AssignExpr assignment) {
        Expression target = Nodes.unwrap(assignment.getTarget());
        Optional<Expression> multiplier = multiplierOf(assignment, target);
        Optional<Node> loop = Nodes.enclosingLoop(assignment);
        if (multiplier.isEmpty() || loop.isEmpty() || !target.isNameExpr()
                || DataValues.isConstant(multiplier.get())) {
            return Optional.empty();
        }

        String name = target.asNameExpr().getNameAsString();
        Optional<Node> declaration = LocalTypes.findDeclaration(target, name);
        Optional<String> type = declaration.flatMap(LocalTypes::declaredType);
        if (type.filter(INTEGER_TYPES::contains).isEmpty() || !LocalTypes.isLocal(target, name)
                || !Loops.isDeclaredOutside(loop.get(), declaration.get())
                || isProtected(assignment) || isShort(loop.get()) || isBackoff(name)) {
            return Optional.empty();
        }

        Violation violation = violation(sourceFile, assignment, message("'" + name + "' типа " + type.get()));
        // result *= i: множитель - счетчик цикла, это факториал или его родня. Иначе множители могут быть малы
        return Optional.of(isCounterOf(loop.get(), multiplier.get())
                ? violation
                : violation.withConfidence(Confidence.SUSPICION));
    }

    private Optional<Expression> multiplierOf(AssignExpr assignment, Expression target) {
        if (assignment.getOperator() == AssignExpr.Operator.MULTIPLY) {
            return Optional.of(assignment.getValue());
        }
        Expression value = Nodes.unwrap(assignment.getValue());
        if (assignment.getOperator() != AssignExpr.Operator.ASSIGN || !value.isBinaryExpr()
                || value.asBinaryExpr().getOperator() != BinaryExpr.Operator.MULTIPLY) {
            return Optional.empty();
        }
        // hash = hash * 31 + value здесь не подходит: это не чистое произведение
        String name = target.toString();
        if (Nodes.unwrap(value.asBinaryExpr().getLeft()).toString().equals(name)) {
            return Optional.of(value.asBinaryExpr().getRight());
        }
        return Nodes.unwrap(value.asBinaryExpr().getRight()).toString().equals(name)
                ? Optional.of(value.asBinaryExpr().getLeft())
                : Optional.empty();
    }

    // LongStream.rangeClosed(1, n).reduce(1, (a, b) -> a * b)
    private Optional<Violation> checkReduce(SourceFile sourceFile, MethodCallExpr call) {
        if (!REDUCE.equals(call.getNameAsString()) || call.getArguments().isEmpty()) {
            return Optional.empty();
        }
        Expression last = Nodes.unwrap(call.getArgument(call.getArguments().size() - 1));
        if (!last.isLambdaExpr() || last.asLambdaExpr().getParameters().size() != PAIR
                || last.asLambdaExpr().getExpressionBody().isEmpty()) {
            return Optional.empty();
        }
        LambdaExpr lambda = last.asLambdaExpr();
        Expression body = Nodes.unwrap(lambda.getExpressionBody().get());
        if (!body.isBinaryExpr() || body.asBinaryExpr().getOperator() != BinaryExpr.Operator.MULTIPLY) {
            return Optional.empty();
        }
        Set<String> parameters = Set.of(
                lambda.getParameter(0).getNameAsString(), lambda.getParameter(1).getNameAsString());
        Set<String> operands = Set.of(Nodes.unwrap(body.asBinaryExpr().getLeft()).toString(),
                Nodes.unwrap(body.asBinaryExpr().getRight()).toString());
        // BigInteger и BigDecimal умножают методом multiply, double переполняется иначе
        boolean numbers = call.toString().contains("Stream") || isIntegerLiteral(call.getArgument(0));
        return parameters.equals(operands) && numbers
                ? Optional.of(violation(sourceFile, call, message("стриме")).withConfidence(Confidence.SUSPICION))
                : Optional.empty();
    }

    private boolean isIntegerLiteral(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        return value.isIntegerLiteralExpr() || value.isLongLiteralExpr();
    }

    // return n * factorial(n - 1)
    private Optional<Violation> checkRecursion(SourceFile sourceFile, MethodDeclaration method) {
        if (method.getBody().isEmpty() || !INTEGER_TYPES.contains(method.getTypeAsString())) {
            return Optional.empty();
        }
        for (BinaryExpr product : method.getBody().get().findAll(BinaryExpr.class)) {
            if (product.getOperator() != BinaryExpr.Operator.MULTIPLY || isProtected(product)) {
                continue;
            }
            boolean recursive = isSelfCall(method, product.getLeft()) != isSelfCall(method, product.getRight());
            Expression other = isSelfCall(method, product.getLeft()) ? product.getRight() : product.getLeft();
            if (recursive && !DataValues.isConstant(other)) {
                Violation violation = violation(sourceFile, product,
                        message("результате '" + method.getNameAsString() + "' типа " + method.getTypeAsString()));
                // n * factorial(n - 1): множитель - сам аргумент. Иначе множители могут быть малы
                boolean byArgument = method.getParameters().stream()
                        .anyMatch(parameter -> parameter.getNameAsString().equals(Nodes.unwrap(other).toString()));
                return Optional.of(byArgument ? violation : violation.withConfidence(Confidence.SUSPICION));
            }
        }
        return Optional.empty();
    }

    private boolean isSelfCall(MethodDeclaration method, Expression expression) {
        Expression value = Nodes.unwrap(expression);
        return value.isMethodCallExpr()
                && value.asMethodCallExpr().getNameAsString().equals(method.getNameAsString())
                && value.asMethodCallExpr().getArguments().size() == method.getParameters().size()
                && value.asMethodCallExpr().getScope().filter(scope -> !scope.isThisExpr()).isEmpty();
    }

    private String message(String place) {
        return "Произведение накапливается в " + place + " без проверки переполнения: когда оно перестанет"
                + " помещаться, Java не сообщит об ошибке, а молча даст неверное число (в long помещается только"
                + " 20!, в int - 12!); умножайте через Math.multiplyExact, чтобы получить исключение, считайте в"
                + " BigInteger либо ограничьте число множителей";
    }

    private boolean isProtected(Node node) {
        Optional<Node> callable = Nodes.enclosingCallable(node);
        if (callable.isEmpty()) {
            return false;
        }
        String text = callable.get().toString();
        boolean hashing = callable.get() instanceof MethodDeclaration method
                && method.getNameAsString().toLowerCase().contains(HASH);
        return hashing || OVERFLOW_CHECKS.stream().anyMatch(text::contains);
    }

    // for (int i = 1; i <= 10; i++): множителей заведомо мало
    private boolean isShort(Node loop) {
        if (!(loop instanceof ForStmt statement) || statement.getCompare().isEmpty()) {
            return false;
        }
        Expression compare = Nodes.unwrap(statement.getCompare().get());
        if (!compare.isBinaryExpr()) {
            return false;
        }
        return isSmall(compare.asBinaryExpr().getLeft()) || isSmall(compare.asBinaryExpr().getRight());
    }

    private boolean isSmall(Expression expression) {
        Optional<Object> constant = ConstantValues.evaluate(Nodes.unwrap(expression));
        return constant.isPresent() && constant.get() instanceof Number number
                && Math.abs(number.longValue()) <= SAFE_STEPS;
    }

    private boolean isBackoff(String name) {
        String lower = name.toLowerCase();
        return BACKOFF_NAMES.stream().anyMatch(lower::contains);
    }

    private boolean isCounterOf(Node loop, Expression multiplier) {
        if (!(loop instanceof ForStmt statement)) {
            return false;
        }
        return DataValues.steppedNames(statement.getUpdate()).stream()
                .anyMatch(counter -> DataValues.mentions(multiplier, counter)
                        || Nodes.unwrap(multiplier).toString().equals(counter));
    }
}
