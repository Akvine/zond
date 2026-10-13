package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ConfigProperty;
import ru.akvine.zond.models.RuleParameter;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Пул потоков большого постоянного размера, который не связан с числом ядер.
 * <p>
 * Для вычислений потоков сверх числа ядер не нужно: работать одновременно может столько потоков,
 * сколько ядер, остальные только добавляют переключений и памяти под стеки. Сто потоков на восьми
 * ядрах считают медленнее, чем восемь. Большой пул уместен для задач, которые ждут ввода-вывода, но
 * и тогда его размер подбирают по нагрузке и выносят в настройку.
 */
@Component
public class FixedThreadPoolSizeRule extends AbstractContextRule {
    private static final RuleParameter MAX_THREADS = new RuleParameter(
            "max-threads", 32, "Размер пула потоков, заданный числом, начиная с которого он считается находкой");

    private static final String EXECUTORS = "Executors";
    // Executors.newFixedThreadPool(100): размер - первый аргумент
    private static final Set<String> SIZED_FACTORIES =
            Set.of("newFixedThreadPool", "newScheduledThreadPool", "newWorkStealingPool");
    // new ThreadPoolExecutor(core, max, ...), new ForkJoinPool(parallelism)
    private static final Set<String> SIZED_TYPES =
            Set.of("ThreadPoolExecutor", "ScheduledThreadPoolExecutor", "ForkJoinPool");
    private static final int THREAD_POOL_SIZES = 2;
    private static final String THREAD_POOL_EXECUTOR = "ThreadPoolExecutor";
    // executor.setCorePoolSize(100) у ThreadPoolTaskExecutor
    private static final Set<String> SIZE_SETTERS = Set.of("setCorePoolSize", "setMaxPoolSize", "setPoolSize");

    // Настройки пулов потоков: и Spring, и собственные (parallel.executor.threads-count)
    private static final Pattern POOL_KEY = Pattern.compile(
            "(?i)^spring\\.task\\.(execution|scheduling)\\.pool\\.(core-size|max-size|size)$"
                    + "|(executor|worker|parallel|async|task).*(threads?|pool)[-_.]?(count|size|number|core|max)"
                    + "|threads?[-_.]?(count|number|size)$");
    // Пулы соединений и потоки веб-сервера ждут ввода-вывода: им большой размер положен
    private static final Pattern IO_POOL = Pattern.compile(
            "(?i)^server\\.|tomcat|jetty|undertow|netty|hikari|datasource|jdbc|redis|lettuce|jedis|mongo|kafka"
                    + "|rabbit|connection|http|grpc");
    private static final Pattern NUMBER = Pattern.compile("\\d{1,9}");

    @Override
    public String code() {
        return RuleCodes.FIXED_THREAD_POOL_SIZE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и настройки и ищет пулы потоков большого постоянного размера, не связанного"
                + " с числом ядер";
    }

    @Override
    public List<RuleParameter> parameters() {
        return List.of(MAX_THREADS);
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : context.sources()) {
            checkCode(sourceFile, violations);
        }
        for (ConfigFile configFile : context.configFiles()) {
            // Профили разработки и тестов в рабочую среду не попадают
            if (!configFile.isNonProduction()) {
                checkConfig(configFile, violations);
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    private void checkCode(SourceFile sourceFile, List<Violation> violations) {
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            boolean factory = SIZED_FACTORIES.contains(call.getNameAsString())
                    && call.getScope().filter(scope -> EXECUTORS.equals(MethodCalls.receiverName(scope))).isPresent();
            boolean setter = SIZE_SETTERS.contains(call.getNameAsString()) && call.getArguments().size() == 1;
            if ((factory || setter) && !call.getArguments().isEmpty() && !TestClasses.isInside(call)) {
                large(call.getArgument(0)).ifPresent(size -> violations.add(
                        violation(sourceFile, call.getName(), message("'" + Nodes.text(call) + "'", size))));
            }
        }
        for (ObjectCreationExpr creation : sourceFile.unit().findAll(ObjectCreationExpr.class)) {
            String type = creation.getType().getNameAsString();
            if (!SIZED_TYPES.contains(type) || creation.getArguments().isEmpty() || TestClasses.isInside(creation)) {
                continue;
            }
            // У ThreadPoolExecutor размеров два: основной и наибольший
            int sizes = THREAD_POOL_EXECUTOR.equals(type) ? THREAD_POOL_SIZES : 1;
            creation.getArguments().stream()
                    .limit(sizes)
                    .map(this::large)
                    .flatMapToInt(OptionalInt::stream)
                    .max()
                    .ifPresent(size -> violations.add(
                            violation(sourceFile, creation, message("'new " + type + "(...)'", size))));
        }
    }

    private void checkConfig(ConfigFile configFile, List<Violation> violations) {
        for (ConfigProperty property : configFile.properties()) {
            String key = property.key();
            String value = property.value() == null ? "" : property.value().trim();
            if (!POOL_KEY.matcher(key).find() || IO_POOL.matcher(key).find() || !NUMBER.matcher(value).matches()) {
                continue;
            }
            int size = Integer.parseInt(value);
            if (size >= value(MAX_THREADS)) {
                // О том, что это пул потоков, говорит только название свойства
                violations.add(violation(configFile.path(), property.line(), message("Свойство '" + key + "'", size))
                        .withConfidence(Confidence.SUSPICION));
            }
        }
    }

    /**
     * @return размер пула, если он задан числом (прямо или константой) и достигает порога. Размер,
     * вычисленный от числа ядер или взятый из настроек, сюда не попадает.
     */
    private OptionalInt large(Expression size) {
        Expression value = Nodes.unwrap(size);
        if (value.isNameExpr()) {
            // private static final int THREADS = 100;
            Optional<Expression> constant = LocalTypes.findDeclaration(value)
                    .filter(declaration -> declaration instanceof VariableDeclarator)
                    .flatMap(declaration -> ((VariableDeclarator) declaration).getInitializer());
            value = constant.map(Nodes::unwrap).orElse(value);
        }
        if (!value.isIntegerLiteralExpr() || !NUMBER.matcher(value.asIntegerLiteralExpr().getValue()).matches()) {
            return OptionalInt.empty();
        }
        int threads = Integer.parseInt(value.asIntegerLiteralExpr().getValue());
        return threads >= value(MAX_THREADS) ? OptionalInt.of(threads) : OptionalInt.empty();
    }

    private static String message(String subject, int size) {
        return subject + " задает пул из " + size + " потоков числом, не связанным с числом ядер: вычислениям"
                + " потоков сверх ядер не нужно - лишние только добавляют переключений и памяти под стеки, и"
                + " работа идет медленнее; для вычислений берите Runtime.getRuntime().availableProcessors(), а"
                + " если задачи ждут ввода-вывода, подберите размер по нагрузке и вынесите его в настройку";
    }
}
