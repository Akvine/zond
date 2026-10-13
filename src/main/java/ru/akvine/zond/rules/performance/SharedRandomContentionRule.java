package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Loops;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.SpringBeans;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Один генератор случайных чисел на все потоки.
 * <p>
 * {@code SecureRandom} выдает числа под общей блокировкой, {@code Random} заставляет потоки соревноваться
 * за общее состояние. Пока поток один, это незаметно; когда значения генерируют несколько потоков
 * сразу, они ждут друг друга, и с ростом числа потоков работа не ускоряется, а замедляется.
 * <p>
 * Если генератор создан без seed, воспроизводимость последовательности не нужна - значит, его можно
 * заменить: дать каждому потоку или задаче свой быстрый генератор
 * ({@code RandomGeneratorFactory.of("L64X128MixRandom").create()}) либо брать
 * {@code ThreadLocalRandom.current()}. Секретов (токены, пароли, ключи) это не касается: для них
 * нужен именно {@code SecureRandom}, и правило о таких генераторах молчит.
 */
@Component
public class SharedRandomContentionRule extends AbstractRule implements ProjectRule {
    private static final String SECURE_RANDOM = "SecureRandom";
    private static final Set<String> RANDOM_TYPES = Set.of(SECURE_RANDOM, "Random");
    private static final String SET_SEED = "setSeed";

    // Секрет обязан быть непредсказуемым: такой генератор менять на быстрый нельзя
    private static final Pattern SECRET_CONTEXT = Pattern.compile(
            "(?i)(token|password|passwd|secret|salt|nonce|otp|session|apikey|api_key|captcha|verification|crypt"
                    + "|cipher|signature|jwt|csrf|credential|keygen|privatekey|secretkey)");

    // Признаки того, что код проекта выполняется несколькими потоками сразу
    private static final Set<String> CONCURRENT_ANNOTATIONS = Set.of(
            "RestController", "Controller", "Async", "Scheduled", "RabbitListener", "KafkaListener", "JmsListener",
            "SqsListener", "MessageMapping");
    private static final Set<String> CONCURRENT_TYPES = Set.of(
            "ExecutorService", "Executor", "ThreadPoolTaskExecutor", "CompletableFuture", "ForkJoinPool", "Thread");
    private static final Set<String> CONCURRENT_CALLS = Set.of("parallelStream", "parallel");

    private record Candidate(SourceFile file, VariableDeclarator variable) {
    }

    @Override
    public String code() {
        return RuleCodes.SHARED_RANDOM_CONTENTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет общий на все потоки генератор случайных чисел (Random, SecureRandom),"
                + " созданный без seed и не связанный с секретами";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        List<Candidate> candidates = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (FieldDeclaration field : sourceFile.unit().findAll(FieldDeclaration.class)) {
                generators(field).forEach(variable -> candidates.add(new Candidate(sourceFile, variable)));
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        // Потоки ищутся по всему проекту - и только когда есть о чем сообщать
        boolean concurrent = isConcurrent(sourceFiles);
        return candidates.stream()
                .map(candidate -> violation(candidate.file(), candidate.variable(), message(candidate.variable()))
                        // SecureRandom без единого слова о секретах - скорее всего данные, но не наверняка
                        .withConfidence(concurrent && !isSecure(candidate.variable())
                                ? Confidence.PROBABLE
                                : Confidence.SUSPICION))
                .toList();
    }

    private List<VariableDeclarator> generators(FieldDeclaration field) {
        Optional<ClassOrInterfaceDeclaration> owner = field.findAncestor(ClassOrInterfaceDeclaration.class)
                .filter(type -> isShared(field, type));
        if (owner.isEmpty() || TestClasses.isInside(field)) {
            return List.of();
        }
        return field.getVariables().stream()
                .filter(variable -> isUnseededGenerator(variable) && isHeavilyUsed(variable)
                        && !isSecret(owner.get(), variable))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    // Статическое поле и поле бина-одиночки - один экземпляр на все потоки
    private boolean isShared(FieldDeclaration field, ClassOrInterfaceDeclaration owner) {
        return field.isStatic() || SpringBeans.isBean(owner);
    }

    // new Random(), new SecureRandom(): seed не задан ни при создании, ни позже
    private boolean isUnseededGenerator(VariableDeclarator variable) {
        Optional<Expression> initializer = variable.getInitializer().map(Nodes::unwrap);
        boolean created = initializer.filter(Expression::isObjectCreationExpr)
                .map(Expression::asObjectCreationExpr)
                .filter(creation -> RANDOM_TYPES.contains(creation.getType().getNameAsString())
                        && creation.getArguments().isEmpty())
                .isPresent();
        return created && usages(variable).stream().noneMatch(usage -> usage.getParentNode()
                .filter(parent -> parent instanceof MethodCallExpr call && SET_SEED.equals(call.getNameAsString()))
                .isPresent());
    }

    private boolean isSecure(VariableDeclarator variable) {
        return variable.getInitializer()
                .filter(initializer -> initializer.toString().contains(SECURE_RANDOM))
                .isPresent();
    }

    // Одно число на запрос - не узкое место. Узким местом генератор становится, когда из него берут
    // значения в цикле либо отдают его другому коду, который делает это сам
    private boolean isHeavilyUsed(VariableDeclarator variable) {
        return usages(variable).stream().anyMatch(usage -> Loops.isRepeated(usage) || escapes(usage));
    }

    // Передан аргументом, присвоен или возвращен: дальше им пользуются уже другие
    private boolean escapes(NameExpr usage) {
        Node parent = usage.getParentNode().orElse(null);
        return !(parent instanceof MethodCallExpr call && call.getScope().filter(scope -> scope == usage).isPresent());
    }

    private List<NameExpr> usages(VariableDeclarator variable) {
        String name = variable.getNameAsString();
        return variable.findAncestor(ClassOrInterfaceDeclaration.class)
                .map(owner -> owner.findAll(NameExpr.class).stream()
                        .filter(usage -> usage.getNameAsString().equals(name))
                        .toList())
                .orElse(List.of());
    }

    // О назначении генератора говорят имена: самого поля, класса и методов, где им пользуются
    private boolean isSecret(ClassOrInterfaceDeclaration owner, VariableDeclarator variable) {
        if (SECRET_CONTEXT.matcher(variable.getNameAsString()).find()
                || SECRET_CONTEXT.matcher(owner.getNameAsString()).find()) {
            return true;
        }
        return usages(variable).stream()
                .map(usage -> usage.findAncestor(MethodDeclaration.class))
                .flatMap(Optional::stream)
                .anyMatch(method -> SECRET_CONTEXT.matcher(method.getNameAsString()).find());
    }

    private boolean isConcurrent(List<SourceFile> sourceFiles) {
        for (SourceFile sourceFile : sourceFiles) {
            boolean annotated = sourceFile.unit().findAll(AnnotationExpr.class).stream()
                    .anyMatch(annotation -> CONCURRENT_ANNOTATIONS.contains(annotation.getNameAsString()));
            boolean typed = sourceFile.unit().findAll(ClassOrInterfaceType.class).stream()
                    .anyMatch(type -> CONCURRENT_TYPES.contains(type.getNameAsString()));
            boolean parallel = sourceFile.unit().findAll(MethodCallExpr.class).stream()
                    .anyMatch(call -> CONCURRENT_CALLS.contains(call.getNameAsString()));
            if (annotated || typed || parallel) {
                return true;
            }
        }
        return false;
    }

    private String message(VariableDeclarator variable) {
        boolean secure = isSecure(variable);
        return "Генератор '" + variable.getNameAsString() + "' - один на все потоки и создан без seed: "
                + (secure
                ? "SecureRandom выдает числа под общей блокировкой"
                : "потоки соревнуются за общее состояние Random")
                + ", и при одновременной генерации они ждут друг друга - с ростом числа потоков работа"
                + " замедляется; воспроизводимость без seed не нужна, поэтому дайте каждому потоку или задаче"
                + " свой быстрый генератор: RandomGeneratorFactory.of(\"L64X128MixRandom\").create() либо"
                + " ThreadLocalRandom.current()"
                + (secure ? "; SecureRandom оставьте только для секретов и для начального состояния" : "");
    }
}
