package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Подбор уникального короткого кода случайным перебором: сгенерировать, проверить, что такого еще нет, и
 * повторить. Коротких кодов конечное число: когда они почти закончатся, цикл без предела попыток крутится
 * очень долго, а когда закончатся совсем - бесконечно, и каждая попытка - еще один запрос в базу. Цикл с
 * пределом, который после последней попытки возвращает null или последний, уже занятый код, прячет ту же
 * ошибку до вставки дубликата. После последней попытки нужно бросать исключение.
 */
@Component
public class UniqueCodeGenerationRule extends AbstractRule {
    // RandomStringUtils.randomNumeric(6), random.nextInt(1_000_000), StringUtils.randomAlphanumeric(8),
    // RandomStringUtils.secure().nextNumeric(6) в commons-lang с версии 3.16
    private static final Pattern RANDOM_CALL = Pattern.compile(
            "random[A-Z]\\w*|random|next(Int|Long|Numeric|Alphabetic|Alphanumeric|Ascii|Print|Graph|Hex)|ints|longs");
    private static final String RANDOM_UUID = "randomUUID";
    // randomUUID().toString().substring(0, 8), Math.abs(randomUUID().hashCode()) % 1_000_000: короткий обрезок UUID
    private static final Set<String> UUID_SHORTENERS =
            Set.of("substring", "hashCode", "getMostSignificantBits", "getLeastSignificantBits");
    private static final Set<String> APPENDS = Set.of("append", "insert");

    // repository.existsByCode(code), findByUuid(code).isPresent(), usedCodes.contains(code)
    private static final Pattern UNIQUENESS_CHECK = Pattern.compile(
            "exists\\w*|find\\w*By\\w*|get\\w*By\\w*|count\\w*By\\w*|contains|containsKey|isUnique\\w*|isTaken\\w*"
                    + "|isFree\\w*|isAvailable\\w*|isUsed\\w*");

    // Сколько значений дает символ кода такого вида
    private static final Map<String, Integer> ALPHABETS = Map.ofEntries(
            Map.entry("randomNumeric", 10), Map.entry("randomAlphabetic", 52), Map.entry("randomAlphanumeric", 62),
            Map.entry("randomAscii", 95), Map.entry("randomPrint", 95), Map.entry("randomGraph", 94),
            Map.entry("nextNumeric", 10), Map.entry("nextAlphabetic", 52), Map.entry("nextAlphanumeric", 62),
            Map.entry("nextAscii", 95), Map.entry("nextPrint", 95), Map.entry("nextGraph", 94), Map.entry("nextHex", 16));
    // От миллиарда вариантов коды на практике не кончаются: находка остается, но с уверенностью "подозрение"
    private static final double PRACTICALLY_ENDLESS = 1e9;

    private static final Set<BinaryExpr.Operator> COMPARISONS = Set.of(
            BinaryExpr.Operator.LESS, BinaryExpr.Operator.LESS_EQUALS,
            BinaryExpr.Operator.GREATER, BinaryExpr.Operator.GREATER_EQUALS);
    // Распространить "случайность" по присваиваниям: suffix = random(6); name = base + suffix
    private static final int PROPAGATION_PASSES = 3;

    @Override
    public String code() {
        return RuleCodes.UNIQUE_CODE_GENERATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет подбор уникального случайного кода в цикле без предела попыток либо без"
                + " исключения после последней попытки";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (Statement loop : sourceFile.unit().findAll(Statement.class, this::isLoop)) {
            if (TestClasses.isInside(loop)) {
                continue;
            }
            Set<String> generated = generatedVariables(loop);
            Optional<MethodCallExpr> check = uniquenessCheck(loop, generated);
            // Цикл должен заканчиваться, когда код нашелся: return, break либо проверка в условии цикла.
            // Пакетная генерация (for по числу нужных кодов) - другой случай
            if (generated.isEmpty() || check.isEmpty() || !stopsOnSuccess(loop, check.get())) {
                continue;
            }
            Confidence confidence = combinations(loop).orElse(0) >= PRACTICALLY_ENDLESS
                    ? Confidence.SUSPICION
                    : Confidence.PROBABLE;
            String checkText = Nodes.text(check.get());
            if (!isBounded(loop)) {
                violations.add(violation(sourceFile, loop,
                        "Уникальный код подбирается случайным перебором без предела попыток (проверка '" + checkText
                                + "'): когда коды почти закончатся, цикл будет крутиться очень долго, а когда"
                                + " закончатся совсем - бесконечно, и каждая попытка - еще одна проверка; ограничьте"
                                + " число попыток и после последней бросайте исключение").withConfidence(confidence));
            } else if (!throwsWhenExhausted(loop)) {
                violations.add(violation(sourceFile, loop,
                        "После последней попытки подобрать уникальный код (проверка '" + checkText + "') исключение"
                                + " не бросается: метод вернет null либо последний, уже занятый код, и ошибка всплывет"
                                + " позже - дубликатом или NullPointerException; бросайте исключение сразу после"
                                + " цикла").withConfidence(confidence));
            }
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

    private boolean isLoop(Statement statement) {
        return statement instanceof WhileStmt || statement instanceof DoStmt || statement instanceof ForStmt;
    }

    // Переменные цикла, в которые попадает случайное значение: code = randomNumeric(6); name = base + "-" + code
    private Set<String> generatedVariables(Statement loop) {
        Set<String> generated = new HashSet<>();
        for (int pass = 0; pass < PROPAGATION_PASSES; pass++) {
            for (VariableDeclarator variable : loop.findAll(VariableDeclarator.class)) {
                if (variable.getInitializer().filter(value -> isRandom(value, generated)).isPresent()) {
                    generated.add(variable.getNameAsString());
                }
            }
            for (AssignExpr assign : loop.findAll(AssignExpr.class)) {
                if (assign.getTarget().isNameExpr() && isRandom(assign.getValue(), generated)) {
                    generated.add(assign.getTarget().asNameExpr().getNameAsString());
                }
            }
            // code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())))
            for (MethodCallExpr call : loop.findAll(MethodCallExpr.class)) {
                if (APPENDS.contains(call.getNameAsString()) && call.getScope().filter(Expression::isNameExpr).isPresent()
                        && call.getArguments().stream().anyMatch(argument -> isRandom(argument, generated))) {
                    generated.add(call.getScope().get().asNameExpr().getNameAsString());
                }
            }
        }
        return generated;
    }

    private boolean stopsOnSuccess(Statement loop, MethodCallExpr check) {
        boolean checkedInCondition = conditionOf(loop)
                .filter(condition -> condition == check || condition.isAncestorOf(check))
                .isPresent();
        return checkedInCondition || bodyOf(loop).findAll(Statement.class, statement -> statement.isReturnStmt()
                        || statement.isBreakStmt())
                .stream()
                .anyMatch(exit -> nearestLoop(exit) == loop);
    }

    private Statement bodyOf(Statement loop) {
        if (loop instanceof WhileStmt whileLoop) {
            return whileLoop.getBody();
        }
        if (loop instanceof DoStmt doLoop) {
            return doLoop.getBody();
        }
        return ((ForStmt) loop).getBody();
    }

    // Выход из вложенного цикла или лямбды к этому циклу отношения не имеет
    private Node nearestLoop(Statement exit) {
        Node current = exit.getParentNode().orElse(null);
        while (current != null && !(current instanceof Statement statement && isLoop(statement))
                && !(current instanceof LambdaExpr)) {
            current = current.getParentNode().orElse(null);
        }
        return current;
    }

    // В выражении есть случайный вызов либо уже случайная переменная. UUID.randomUUID() целиком не кончается:
    // считается только его обрезок - randomUUID().toString().substring(0, 8)
    private boolean isRandom(Expression expression, Set<String> generated) {
        boolean byCall = expression.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> RANDOM_CALL.matcher(call.getNameAsString()).matches()
                        && !RANDOM_UUID.equals(call.getNameAsString())
                        || UUID_SHORTENERS.contains(call.getNameAsString()) && call.toString().contains(RANDOM_UUID));
        return byCall || expression.findAll(NameExpr.class).stream()
                .anyMatch(name -> generated.contains(name.getNameAsString()));
    }

    // Проверка, есть ли уже такой код: в условии цикла либо в его теле, с самим кодом в аргументах или объекте
    private Optional<MethodCallExpr> uniquenessCheck(Statement loop, Set<String> generated) {
        return loop.findAll(MethodCallExpr.class).stream()
                .filter(call -> UNIQUENESS_CHECK.matcher(call.getNameAsString()).matches())
                .filter(call -> call.getArguments().stream()
                        .anyMatch(argument -> argument.findAll(NameExpr.class).stream()
                                .anyMatch(name -> generated.contains(name.getNameAsString()))
                                || argument.isNameExpr() && generated.contains(argument.asNameExpr().getNameAsString())))
                .findFirst();
    }

    // for (i < attempts), while (attempt++ < MAX && exists(code)), if (++attempts > MAX) throw / break
    private boolean isBounded(Statement loop) {
        Optional<Expression> condition = conditionOf(loop);
        if (condition.filter(this::hasComparison).isPresent()) {
            return true;
        }
        return loop.findAll(IfStmt.class).stream()
                .filter(branch -> hasComparison(branch.getCondition()))
                .anyMatch(branch -> leavesLoop(branch.getThenStmt()));
    }

    private Optional<Expression> conditionOf(Statement loop) {
        if (loop instanceof WhileStmt whileLoop) {
            return Optional.of(whileLoop.getCondition());
        }
        if (loop instanceof DoStmt doLoop) {
            return Optional.of(doLoop.getCondition());
        }
        return ((ForStmt) loop).getCompare();
    }

    private boolean hasComparison(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        return value instanceof BinaryExpr binary && COMPARISONS.contains(binary.getOperator())
                || value.findAll(BinaryExpr.class).stream().anyMatch(binary -> COMPARISONS.contains(binary.getOperator()));
    }

    private boolean leavesLoop(Statement statement) {
        return statement.isThrowStmt() || statement.isBreakStmt() || statement.isReturnStmt()
                || statement.isBlockStmt() && statement.asBlockStmt().getStatements().stream().anyMatch(this::leavesLoop);
    }

    // Попытки кончились: сразу за циклом throw либо if (code == null) throw; внутри цикла - if (++attempts > MAX) throw
    private boolean throwsWhenExhausted(Statement loop) {
        boolean insideLoop = loop.findAll(IfStmt.class).stream()
                .filter(branch -> hasComparison(branch.getCondition()))
                .anyMatch(branch -> !branch.getThenStmt().findAll(ThrowStmt.class).isEmpty());
        if (insideLoop) {
            return true;
        }
        Optional<Statement> next = nextStatement(loop);
        return next.filter(statement -> statement.isThrowStmt()
                || statement.isIfStmt() && !statement.asIfStmt().getThenStmt().findAll(ThrowStmt.class).isEmpty())
                .isPresent();
    }

    private Optional<Statement> nextStatement(Statement loop) {
        Node parent = loop.getParentNode().orElse(null);
        if (!(parent instanceof BlockStmt block)) {
            return Optional.empty();
        }
        NodeList<Statement> statements = block.getStatements();
        int index = statements.indexOf(loop);
        return index >= 0 && index + 1 < statements.size() ? Optional.of(statements.get(index + 1)) : Optional.empty();
    }

    // randomNumeric(6) - миллион кодов, nextInt(10000) - десять тысяч; длина из настроек неизвестна
    private OptionalDouble combinations(Statement loop) {
        return loop.findAll(MethodCallExpr.class).stream()
                .map(this::combinationsOf)
                .flatMap(Optional::stream)
                .mapToDouble(Double::doubleValue)
                .min();
    }

    private Optional<Double> combinationsOf(MethodCallExpr call) {
        String name = call.getNameAsString();
        Optional<Long> argument = call.getArguments().size() == 1 && call.getArgument(0).isIntegerLiteralExpr()
                ? Optional.of(call.getArgument(0).asIntegerLiteralExpr().asNumber().longValue())
                : Optional.empty();
        if (ALPHABETS.containsKey(name)) {
            return argument.map(length -> Math.pow(ALPHABETS.get(name), length));
        }
        if (name.equals("nextInt") || name.equals("nextLong")) {
            return argument.map(Long::doubleValue);
        }
        return Optional.empty();
    }
}
