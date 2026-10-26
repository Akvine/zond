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
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class InsecureRandomRule extends AbstractRule {
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
            ".*(token|password|passwd|secret|salt|nonce|otp|sessionid|apikey|captcha|verification|rememberme|resetkey).*",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String code() {
        return RuleCodes.INSECURE_RANDOM_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет Random и Math.random() при генерации токенов, паролей и других секретов";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (Expression expression : sourceFile.unit().findAll(Expression.class)) {
            if (!isPredictableRandom(expression)) {
                continue;
            }
            // Путь значения до секрета или cookie виден прямо по коду - это уже не догадка по имени вокруг
            Optional<String> named = findSecretContext(expression);
            Optional<String> traced = named.isPresent() ? Optional.empty() : findFlowContext(expression);
            named.or(() -> traced).ifPresent(context -> violations.add(violation(sourceFile, expression,
                    "'" + expression + "' при генерации секрета ('" + context + "'): последовательность обычного"
                            + " генератора предсказуема, по нескольким значениям восстанавливаются остальные;"
                            + " используйте SecureRandom")
                    .withConfidence(traced.isPresent() ? Confidence.PROBABLE : null)));
        }
        return violations;
    }

    // Для чего нужно случайное число, по коду не видно: для задержки повтора Random годится
    @Override
    public Confidence confidence() {
        return Confidence.SUSPICION;
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
                || MethodCalls.isCallOn(expression.asMethodCallExpr(), THREAD_LOCAL_RANDOM, CURRENT));
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
