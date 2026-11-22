package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class InsecureRandomRule extends AbstractRule implements ProjectRule {
    private static final String RANDOM = "Random";
    private static final String MATH = "Math";
    private static final String MATH_RANDOM = "random";
    private static final String THREAD_LOCAL_RANDOM = "ThreadLocalRandom";
    private static final String CURRENT = "current";
    private static final String COOKIE = "Cookie";
    private static final String NEXT_BYTES = "nextBytes";
    // Через сколько присваиваний прослеживается случайное значение внутри метода
    private static final int MAX_FLOW_STEPS = 3;

    // Типы не разрешаем: о том, что значение секретное, судим по имени переменной, метода или класса вокруг
    private static final Pattern SECRET_CONTEXT = Pattern.compile(
            ".*(token|password|passwd|secret|salt|nonce|otp|sessionid|apikey|captcha|verification|rememberme|resetkey"
                    // Коды, которыми подтверждают вход или действие: authCode, smsCode, inviteCode, pinCode
                    + "|(auth|access|activation|confirm|confirmation|verify|invite|invitation|pin|reset|recovery|sms"
                    + "|pass|login|onetime|one_time)_?code).*",
            Pattern.CASE_INSENSITIVE);

    // Случайные строки и числа Apache Commons Lang: RandomStringUtils.randomAlphanumeric(16), RandomUtils.nextInt()
    private static final Set<String> COMMONS_RANDOM = Set.of("RandomStringUtils", "RandomUtils");
    private static final Pattern COMMONS_METHOD = Pattern.compile("random\\w*|next\\w*");
    // Вторая версия библиотеки всегда берет java.util.Random
    private static final String COMMONS_LANG2 = "org.apache.commons.lang.";
    // RandomStringUtils.secure().next(16): генератор выбран явно
    private static final Set<String> SECURE_SOURCES = Set.of("secure", "secureStrong");
    private static final String INSECURE_SOURCE = "insecure";

    @Override
    public String code() {
        return RuleCodes.INSECURE_RANDOM_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет Random и Math.random() при генерации токенов, паролей и других секретов";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        // Свои методы-обертки: String newCode(int length) { return RandomStringUtils.randomAlphanumeric(length); }.
        // Сам метод о назначении значения не говорит - о нем говорит место, где его вызывают
        Map<String, Set<String>> wrappers = new HashMap<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodDeclaration method : sourceFile.findAll(MethodDeclaration.class)) {
                boolean returnsRandom = method.findAll(ReturnStmt.class).stream()
                        .map(ReturnStmt::getExpression)
                        .flatMap(Optional::stream)
                        .map(Nodes::unwrap)
                        .anyMatch(value -> isPredictableRandom(value) && !isOnlyMaybePredictable(value));
                if (returnsRandom && !TestClasses.isInside(method)) {
                    method.findAncestor(TypeDeclaration.class).ifPresent(type -> wrappers
                            .computeIfAbsent(method.getNameAsString(), name -> new HashSet<>())
                            .add(type.getNameAsString()));
                }
            }
        }

        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (Expression expression : sourceFile.findAll(Expression.class)) {
                boolean wrapped = isWrapperCall(expression, wrappers);
                if (!wrapped && !isPredictableRandom(expression)) {
                    continue;
                }
                // Путь значения до секрета или cookie виден прямо по коду - это уже не догадка по имени вокруг
                Optional<String> traced = findFlowContext(expression);
                Optional<String> named = traced.isPresent() ? Optional.empty() : findSecretContext(expression);
                // Третья версия Commons Lang с 3.15 берет SecureRandom: какая версия в проекте, отсюда не видно
                boolean maybe = isOnlyMaybePredictable(expression);
                traced.or(() -> named).ifPresent(context -> violations.add(violation(sourceFile, expression,
                        "'" + expression + "' при генерации секрета ('" + context + "'): "
                                + (wrapped ? "метод берет значение у обычного генератора, а его " : "")
                                + "последовательность" + (wrapped ? "" : " обычного генератора")
                                + " предсказуема, по нескольким значениям восстанавливаются остальные;"
                                + " используйте SecureRandom"
                                + (maybe ? " (у RandomStringUtils и RandomUtils из commons-lang3 до версии 3.15"
                                + " генератор обычный; с 3.16 есть secure())" : ""))
                        .withConfidence(confidenceOf(maybe, traced.isPresent()))));
            }
        }
        return violations;
    }

    // Для чего нужно случайное число, по коду не видно: для задержки повтора Random годится
    @Override
    public Confidence confidence() {
        return Confidence.SUSPICION;
    }

    // Значение дошло до секрета по коду - вероятно; секрет виден только по имени - уверенность правила (null)
    private static Confidence confidenceOf(boolean maybePredictable, boolean traced) {
        if (maybePredictable) {
            return Confidence.SUSPICION;
        }
        return traced ? Confidence.PROBABLE : null;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    // new Random(...), Math.random(), ThreadLocalRandom.current()
    private boolean isPredictableRandom(Expression expression) {
        if (expression.isObjectCreationExpr()) {
            return RANDOM.equals(expression.asObjectCreationExpr().getType().getNameAsString());
        }
        return expression.isMethodCallExpr()
                && (MethodCalls.isCallOn(expression.asMethodCallExpr(), MATH, MATH_RANDOM)
                || MethodCalls.isCallOn(expression.asMethodCallExpr(), THREAD_LOCAL_RANDOM, CURRENT)
                || isCommonsRandom(expression.asMethodCallExpr()));
    }

    // RandomStringUtils.randomNumeric(6), RandomUtils.nextInt(0, 10), RandomStringUtils.insecure().next(8)
    private boolean isCommonsRandom(MethodCallExpr call) {
        if (!COMMONS_METHOD.matcher(call.getNameAsString()).matches() || call.getScope().isEmpty()) {
            return false;
        }
        Expression scope = Nodes.unwrap(call.getScope().get());
        if (scope.isMethodCallExpr()) {
            MethodCallExpr source = scope.asMethodCallExpr();
            return INSECURE_SOURCE.equals(source.getNameAsString()) && source.getScope()
                    .filter(owner -> COMMONS_RANDOM.contains(owner.toString()))
                    .isPresent();
        }
        return scope.isNameExpr() && COMMONS_RANDOM.contains(scope.asNameExpr().getNameAsString())
                && !SECURE_SOURCES.contains(call.getNameAsString());
    }

    /**
     * @return true для статического метода RandomStringUtils и RandomUtils из commons-lang3: до версии 3.15 он
     * берет обычный генератор, с 3.15 - SecureRandom, а версия библиотеки из кода не видна
     */
    private boolean isOnlyMaybePredictable(Expression expression) {
        if (!expression.isMethodCallExpr() || !isCommonsRandom(expression.asMethodCallExpr())
                || !Nodes.unwrap(expression.asMethodCallExpr().getScope().orElseThrow()).isNameExpr()) {
            return false;
        }
        String owner = expression.asMethodCallExpr().getScope().get().toString();
        return expression.findCompilationUnit()
                .map(unit -> unit.getImports().stream()
                        .map(declaration -> declaration.getNameAsString())
                        .noneMatch(name -> name.startsWith(COMMONS_LANG2)
                                && (name.endsWith("." + owner) || name.endsWith(".lang"))))
                .orElse(true);
    }

    // Codes.newCode(8): вызов своего метода, который возвращает значение обычного генератора
    private boolean isWrapperCall(Expression expression, Map<String, Set<String>> wrappers) {
        if (!expression.isMethodCallExpr()) {
            return false;
        }
        MethodCallExpr call = expression.asMethodCallExpr();
        Set<String> owners = wrappers.get(call.getNameAsString());
        if (owners == null) {
            return false;
        }
        // Метод своего класса либо статический метод класса, названного по имени
        return call.getScope()
                .map(scope -> owners.contains(scope.toString()))
                .orElseGet(() -> call.findAncestor(TypeDeclaration.class)
                        .filter(type -> owners.contains(type.getNameAsString()))
                        .isPresent());
    }

    /**
     * @return имя переменной, метода или класса вокруг, которое говорит о секрете
     */
    private Optional<String> findSecretContext(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            String name = null;
            if (current instanceof VariableDeclarator variable) {
                name = variable.getNameAsString();
            } else if (current instanceof MethodDeclaration method) {
                name = method.getNameAsString();
            } else if (current instanceof TypeDeclaration<?> type) {
                name = type.getNameAsString();
            }
            if (name != null && SECRET_CONTEXT.matcher(name).matches()) {
                return Optional.of(name);
            }
            current = current.getParentNode().orElse(null);
        }
        return Optional.empty();
    }

    // float rand = new Random().nextFloat(); String rememberMeKey = Float.toString(rand); new Cookie(name, rememberMeKey):
    // число само в переменной с обычным именем, но дальше становится секретом либо значением cookie
    private Optional<String> findFlowContext(Expression random) {
        Optional<Node> callable = Nodes.enclosingCallable(random);
        Optional<String> holder = holderName(random);
        if (callable.isEmpty() || holder.isEmpty()) {
            return Optional.empty();
        }
        Set<String> carriers = new HashSet<>(Set.of(holder.get()));
        for (int step = 0; step < MAX_FLOW_STEPS; step++) {
            for (ObjectCreationExpr creation : callable.get().findAll(ObjectCreationExpr.class)) {
                if (COOKIE.equals(creation.getType().getNameAsString()) && mentions(creation, carriers)) {
                    return Optional.of(creation.toString());
                }
            }
            // entity.setAuthCode(code), builder.password(value): значение уходит в метод, названный как секрет
            for (MethodCallExpr sink : callable.get().findAll(MethodCallExpr.class)) {
                if (SECRET_CONTEXT.matcher(sink.getNameAsString()).matches()
                        && sink.getArguments().stream().anyMatch(argument -> mentions(argument, carriers))) {
                    return Optional.of(sink.getNameAsString());
                }
            }
            Set<String> reached = new HashSet<>();
            // random.nextBytes(bytes): случайные байты попадают в массив-аргумент
            for (MethodCallExpr fill : callable.get().findAll(MethodCallExpr.class)) {
                if (NEXT_BYTES.equals(fill.getNameAsString()) && fill.getScope().filter(scope -> mentions(scope, carriers)).isPresent()) {
                    filledArray(fill).ifPresent(reached::add);
                }
            }
            for (VariableDeclarator variable : callable.get().findAll(VariableDeclarator.class)) {
                if (variable.getInitializer().filter(value -> mentions(value, carriers)).isPresent()) {
                    if (SECRET_CONTEXT.matcher(variable.getNameAsString()).matches()) {
                        return Optional.of(variable.getNameAsString());
                    }
                    reached.add(variable.getNameAsString());
                }
            }
            if (!carriers.addAll(reached)) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    // Переменная, которой присваивают случайное значение: float rand = new Random().nextFloat(), а у
    // new Random().nextBytes(bytes) - заполняемый массив
    private Optional<String> holderName(Expression random) {
        Optional<String> filled = random.getParentNode()
                .filter(parent -> parent instanceof MethodCallExpr fill && NEXT_BYTES.equals(fill.getNameAsString())
                        && fill.getScope().filter(scope -> scope == random).isPresent())
                .flatMap(parent -> filledArray((MethodCallExpr) parent));
        if (filled.isPresent()) {
            return filled;
        }
        Node current = random.getParentNode().orElse(null);
        while (current instanceof Expression && !(current instanceof AssignExpr)) {
            current = current.getParentNode().orElse(null);
        }
        if (current instanceof VariableDeclarator variable) {
            return Optional.of(variable.getNameAsString());
        }
        return current instanceof AssignExpr assignment && assignment.getTarget().isNameExpr()
                ? Optional.of(assignment.getTarget().asNameExpr().getNameAsString())
                : Optional.empty();
    }

    private Optional<String> filledArray(MethodCallExpr fill) {
        return fill.getArguments().size() == 1 && fill.getArgument(0).isNameExpr()
                ? Optional.of(fill.getArgument(0).asNameExpr().getNameAsString())
                : Optional.empty();
    }

    private boolean mentions(Node node, Set<String> names) {
        return node.findAll(NameExpr.class).stream().anyMatch(name -> names.contains(name.getNameAsString()));
    }
}
