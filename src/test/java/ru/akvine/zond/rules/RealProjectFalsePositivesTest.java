package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.AssertEqualsArgumentOrderRule;
import ru.akvine.zond.rules.codesmell.UnusedClassRule;
import ru.akvine.zond.rules.codesmell.UnusedConfigPropertyRule;
import ru.akvine.zond.rules.codesmell.UnusedMethodRule;
import ru.akvine.zond.rules.concurrency.LockWithoutFinallyRule;
import ru.akvine.zond.rules.concurrency.NonThreadSafeCollectionInBeanRule;
import ru.akvine.zond.rules.concurrency.ScheduledWithoutLockRule;
import ru.akvine.zond.rules.concurrency.ThisEscapeInConstructorRule;
import ru.akvine.zond.rules.concurrency.VolatileNonAtomicRule;
import ru.akvine.zond.rules.exceptions.LostExceptionCauseRule;
import ru.akvine.zond.rules.exceptions.LostStackTraceInLogRule;
import ru.akvine.zond.rules.exceptions.SingleResultWithoutHandlingRule;
import ru.akvine.zond.rules.logical.CaseWithoutLocaleRule;
import ru.akvine.zond.rules.logical.CastWithoutInstanceofRule;
import ru.akvine.zond.rules.logical.CollectionModificationInForEachRule;
import ru.akvine.zond.rules.logical.ConstantAssertionRule;
import ru.akvine.zond.rules.logical.ConstraintOnWrongTypeRule;
import ru.akvine.zond.rules.logical.IncompleteAssertionRule;
import ru.akvine.zond.rules.logical.IteratorNextWithoutHasNextRule;
import ru.akvine.zond.rules.logical.NarrowingCastRule;
import ru.akvine.zond.rules.logical.NullUnboxingRule;
import ru.akvine.zond.rules.logical.NullableDereferenceRule;
import ru.akvine.zond.rules.logical.PossibleNullDereferenceRule;
import ru.akvine.zond.rules.performance.ListShiftInLoopRule;
import ru.akvine.zond.rules.resources.UnboundedExecutorRule;
import ru.akvine.zond.rules.security.CiSecretInVariablesRule;
import ru.akvine.zond.rules.security.RequestBodyWithoutValidRule;
import ru.akvine.zond.rules.security.SecretInConfigRule;
import ru.akvine.zond.rules.security.UnboundedRequestCollectionRule;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ложные срабатывания, найденные прогоном по настоящим проектам: код, о котором правила должны молчать,
 * рядом с похожим кодом, о котором они по-прежнему сообщают.
 */
class RealProjectFalsePositivesTest {
    @TempDir
    Path dir;

    @Test
    void publicApiOfLibraryIsNotDeadCode() {
        // Ни main, ни @SpringBootApplication: открытые классы и методы - API для тех, кто подключит библиотеку
        Map<String, String> library = Map.of(
                "Conditions.java", "public class Conditions { public static Condition ne(String c) { return null; } "
                        + "static void internal() {} }",
                "Helper.java", "class Helper { void help() {} }");
        assertThat(RuleTests.checkProject(new UnusedClassRule(), library)).extracting(Violation::message)
                .singleElement().asString().contains("'Helper'");
        assertThat(RuleTests.checkProject(new UnusedMethodRule(), library)).extracting(Violation::message)
                .allMatch(message -> !message.contains("'Conditions.ne'"))
                .anyMatch(message -> message.contains("'Conditions.internal'"));

        // В приложении тот же открытый класс без единого вызова - мертвый код
        Map<String, String> application = Map.of(
                "Main.java", "public class Main { public static void main(String[] args) {} }",
                "Conditions.java", "public class Conditions { public static Condition ne(String c) { return null; } }");
        assertThat(RuleTests.checkProject(new UnusedClassRule(), application)).extracting(Violation::message)
                .singleElement().asString().contains("'Conditions'");
    }

    @Test
    void lockReleasedOutsideOrWithoutRiskIsNotReported() {
        String code = """
                class Locks {
                    private final ReentrantLock lock = new ReentrantLock();
                    private final ReentrantLock[] segments = new ReentrantLock[16];
                    public void lock() {
                        lock.lock();
                    }
                    public void unlock() {
                        lock.unlock();
                    }
                    long size(int[] counts) {
                        long sum = 0;
                        for (int i = 0; i < segments.length; ++i)
                            segments[i].lock();
                        for (int i = 0; i < segments.length; ++i)
                            sum += counts[i];
                        for (int i = 0; i < segments.length; ++i)
                            segments[i].unlock();
                        return sum;
                    }
                    void risky() {
                        lock.lock();
                        process();
                        lock.unlock();
                    }
                }
                """;

        // Обертка над замком и захват, между которым и освобождением нечему упасть, не находки;
        // вызов метода между lock() и unlock() по-прежнему находка
        assertThat(RuleTests.lines(new LockWithoutFinallyRule(), code)).containsExactly(21);
    }

    @Test
    void variableWithSameNameInAnotherScopeIsNotTheNullableOne() {
        String code = """
                class Columns {
                    void collect(AttributeOverrides multiple, Map<String, String> overrides, Map<String, String> columns, String name) {
                        for (AttributeOverride override : multiple.value()) {
                            overrides.put(override.name(), override.column().name());
                        }
                        String override = columns.get(name);
                        if (override != null && !override.isEmpty()) {
                            use(override.trim());
                        }
                        String raw = columns.get(name);
                        use(raw.trim());
                    }
                }
                """;

        assertThat(RuleTests.lines(new NullableDereferenceRule(), code)).containsExactly(11);
    }

    @Test
    void emptinessCheckOfMapCoversItsValues() {
        String code = """
                class Keys {
                    Object first(Map<String, Object> keys) {
                        if (keys == null || keys.isEmpty()) {
                            return null;
                        }
                        return keys.values().iterator().next();
                    }
                    Object unchecked(Map<String, Object> keys) {
                        return keys.values().iterator().next();
                    }
                }
                """;

        assertThat(RuleTests.lines(new IteratorNextWithoutHasNextRule(), code)).containsExactly(9);
    }

    @Test
    void walkUpClassHierarchyIsNotLoopOverData() {
        String code = """
                class Registry {
                    List<Class<?>> hierarchy(Class<?> type) {
                        List<Class<?>> hierarchy = new ArrayList<>();
                        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                            hierarchy.addFirst(c);
                        }
                        return hierarchy;
                    }
                    List<Item> reversed(List<Item> items) {
                        List<Item> result = new ArrayList<>();
                        for (Item item : items) {
                            result.add(0, item);
                        }
                        return result;
                    }
                }
                """;

        // Глубина иерархии классов - единицы, а не объем данных
        assertThat(RuleTests.lines(new ListShiftInLoopRule(), code)).containsExactly(12);
    }

    @Test
    void scheduledTaskSafeOnEveryInstanceIsNotReported() {
        String code = """
                class Jobs {
                    private static final String SELECT_PENDING = "select * from outbox where sent_at is null for update skip locked";
                    private static final String DELETE_EXPIRED = "delete from idempotency_key where expires_at < ?";
                    private final Map<String, Instant> blockedCache = new ConcurrentHashMap<>();
                    @Scheduled(fixedDelay = 1000)
                    public void clearCache() {
                        blockedCache.entrySet().removeIf(entry -> entry.getValue().isBefore(Instant.now()));
                    }
                    @Scheduled(fixedDelay = 1000)
                    public void removeTempFiles() throws IOException {
                        Files.deleteIfExists(Path.of("/tmp/upload.tmp"));
                    }
                    @Scheduled(fixedDelay = 1000)
                    public void publish() {
                        transactions.execute(status -> publishBatch());
                    }
                    private int publishBatch() {
                        return jdbc.query(SELECT_PENDING, mapper).size();
                    }
                    @Scheduled(cron = "0 7 * * * *")
                    public int removeExpired() {
                        return jdbc.update(DELETE_EXPIRED, Instant.now());
                    }
                    @Scheduled(fixedDelay = 1000)
                    public void notifyUsers() {
                        userRepository.findAll().forEach(user -> mailSender.send(user.email()));
                    }
                }
                """;

        // Свой кеш и свои временные файлы чистит каждый экземпляр; SKIP LOCKED и удаление по условию
        // безопасны при одновременном запуске; рассылка по пользователям - нет
        assertThat(RuleTests.lines(new ScheduledWithoutLockRule(), code)).containsExactly(24);
    }

    @Test
    void queryReturningExactlyOneRowNeedsNoEmptyHandling() {
        String code = """
                class Queries {
                    private static final String COUNT_PENDING = "select count(*) from outbox where sent_at is null";
                    Date now() {
                        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP FROM DUAL", Date.class);
                    }
                    long next() {
                        return jdbc.queryForObject(nextValueSql, Long.class);
                    }
                    long pending() {
                        return jdbc.queryForObject(COUNT_PENDING, Long.class);
                    }
                    Order insert(MapSqlParameterSource values) {
                        String sql = SqlBuilder.insertInto(table).values(values).returning(columns).build();
                        return jdbc.queryForObject(sql, values, mapper);
                    }
                    Order find(long id) {
                        return jdbc.queryForObject("select * from orders where id = ?", mapper, id);
                    }
                }
                """;

        assertThat(RuleTests.lines(new SingleResultWithoutHandlingRule(), code)).containsExactly(17);
    }

    @Test
    void numberUnderTokenNameIsNotSecret() {
        String config = """
                app.rate-limit.refill-tokens=60
                app.auth.token-ttl=15m
                app.auth.api-token=d8f2a91c44
                app.auth.admin-password=123456
                """;
        List<String> lines = new ProjectFixture(dir)
                .write("src/main/resources/application.properties", config)
                .context().configFiles().stream()
                .flatMap(file -> new SecretInConfigRule().checkConfig(file).stream())
                .map(violation -> violation.line() + "")
                .toList();

        // Число и срок - настройка, а пароль из цифр - все равно секрет
        assertThat(lines).containsExactly("3", "4");
    }

    @Test
    void entityIsNotRequestDto() {
        Map<String, String> sources = Map.of(
                "User.java", """
                        @Entity
                        class User {
                            @Email
                            private String email;
                            @Transient
                            private Collection<GrantedAuthority> authorities;
                        }
                        """,
                "CreateUserRequest.java", """
                        class CreateUserRequest {
                            @Email
                            private String email;
                            private List<String> roles;
                        }
                        """);

        assertThat(RuleTests.checkProject(new UnboundedRequestCollectionRule(), sources)).extracting(Violation::message)
                .singleElement().asString().contains("'CreateUserRequest'");
    }

    @Test
    void assigningParameterToVolatileFieldIsAtomic() {
        String code = """
                class Metric {
                    private volatile Timer timer;
                    private volatile int count;
                    void setTimer(Timer timer) {
                        this.timer = timer;
                    }
                    void increment() {
                        count = count + 1;
                    }
                }
                """;

        assertThat(RuleTests.lines(new VolatileNonAtomicRule(), code)).containsExactly(8);
    }

    @Test
    void expectedFailureIsLoggedWithoutStack() {
        String code = """
                class Login {
                    void login(String user) {
                        try {
                            authenticate(user);
                        } catch (BadCredentialsException | DisabledException | LockedException e) {
                            log.warn("Login failed for user '{}': {}", user, e.toString());
                        }
                        try {
                            authenticate(user);
                        } catch (RuntimeException e) {
                            log.error("Login failed: {}", e.getMessage());
                        }
                    }
                }
                """;

        assertThat(RuleTests.lines(new LostStackTraceInLogRule(), code)).containsExactly(11);
    }

    @Test
    void schedulerWithOnlyPeriodicTasksIsNotUnbounded() {
        String code = """
                class Printers {
                    private final ScheduledExecutorService printer = Executors.newSingleThreadScheduledExecutor();
                    private final ScheduledExecutorService delayed = Executors.newSingleThreadScheduledExecutor();
                    void start() {
                        printer.scheduleAtFixedRate(this::print, 1, 1, TimeUnit.MINUTES);
                    }
                    void onEvent(Event event) {
                        delayed.schedule(() -> handle(event), 5, TimeUnit.SECONDS);
                    }
                }
                """;

        // Повторяющаяся задача стоит в очереди одна; разовые задачи на каждое событие копятся
        assertThat(RuleTests.lines(new UnboundedExecutorRule(), code)).containsExactly(3);
    }

    @Test
    void uuidAndHexHaveNoLocaleSensitiveLetters() {
        String code = """
                class Ids {
                    String id() {
                        return UUID.randomUUID().toString().toUpperCase();
                    }
                    String hex(int value) {
                        return Integer.toHexString(value).toUpperCase();
                    }
                    String code(Status status) {
                        return status.name().toLowerCase();
                    }
                }
                """;

        assertThat(RuleTests.lines(new CaseWithoutLocaleRule(), code)).containsExactly(9);
    }

    @Test
    void rangeCheckedOrSmallCastIsNotReported() {
        String code = """
                class Sizes {
                    int size(long sum) {
                        if (sum > Integer.MAX_VALUE)
                            return Integer.MAX_VALUE;
                        else
                            return (int) sum;
                    }
                    int days(LocalDate from, LocalDate to) {
                        return (int) Duration.between(from.atStartOfDay(), to.atStartOfDay()).toDays();
                    }
                    int otp(long next) {
                        return (int) next;
                    }
                }
                """;

        assertThat(RuleTests.lines(new NarrowingCastRule(), code)).containsExactly(12);
    }

    @Test
    void jmixPropertiesAreReadByFramework() {
        List<String> lines = new ProjectFixture(dir)
                .source("App.java", "@Configuration class App { @Value(\"${app.title}\") String title; }")
                .write("src/main/resources/application.properties", """
                        app.title=Demo
                        main.liquibase.change-log=ru/demo/liquibase/changelog.xml
                        jmix.ui.login-view-id=LoginView
                        app.unused=1
                        """)
                .lines(new UnusedConfigPropertyRule());

        assertThat(lines).containsExactly("application.properties:4");
    }

    @Test
    void requestBodyWithoutConstraintsNeedsNoValid() {
        Map<String, String> sources = Map.of(
                "WebhookController.java", """
                        import org.telegram.telegrambots.meta.api.objects.Update;
                        @RestController
                        class WebhookController {
                            void onUpdate(@RequestBody Update update) {}
                            void stop(@RequestBody List<Long> advertIds) {}
                            void note(@RequestBody NoteDto note) {}
                            void create(@RequestBody OrderDto order) {}
                            void batch(@RequestBody List<OrderDto> orders) {}
                        }
                        """,
                "NoteDto.java", "class NoteDto { private String text; }",
                "OrderDto.java", "class OrderDto { @NotBlank private String number; }");

        // Класс библиотеки, список чисел и DTO без ограничений проверять нечем
        assertThat(RuleTests.checkProject(new RequestBodyWithoutValidRule(), sources)).extracting(Violation::line)
                .containsExactly(7, 8);
    }

    @Test
    void jetbrainsNotNullIsNotBeanValidation() {
        String jetbrains = """
                import org.jetbrains.annotations.NotNull;
                class Listener {
                    @NotNull
                    private long version;
                }
                """;
        String validation = """
                import jakarta.validation.constraints.NotNull;
                class Settings {
                    @NotNull
                    private long timeout;
                }
                """;

        assertThat(RuleTests.check(new ConstraintOnWrongTypeRule(), jetbrains)).isEmpty();
        assertThat(RuleTests.lines(new ConstraintOnWrongTypeRule(), validation)).containsExactly(3);
    }

    @Test
    void directNullCheckWinsOverImplication() {
        String code = """
                class ProfileValidator {
                    void verify(LocalDate startDate, LocalDate endDate, String duration) {
                        if (startDate == null && endDate == null && StringUtils.isBlank(duration)) {
                            throw new ValidationException("empty");
                        }
                        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
                            throw new ValidationException("after");
                        }
                        if (endDate != null) {
                            startDate.isBefore(endDate);
                        }
                    }
                }
                """;

        // Проверка 'startDate != null' прямо в условии важнее вывода "если endDate не null, startDate - null"
        assertThat(RuleTests.checkProject(new PossibleNullDereferenceRule(), Map.of("ProfileValidator.java", code)))
                .extracting(Violation::line).containsExactly(10);
    }

    @Test
    void badInputTranslatedToValidationErrorKeepsNoCause() {
        String code = """
                class WeightValidator {
                    double parse(String weight) {
                        try {
                            return Double.parseDouble(weight);
                        } catch (NumberFormatException exception) {
                            throw new ValidationException("Weight must be a number");
                        }
                    }
                    byte[] read(Path file) {
                        try {
                            return Files.readAllBytes(file);
                        } catch (IOException exception) {
                            throw new FileReadException("Can't read file");
                        }
                    }
                }
                """;

        assertThat(RuleTests.lines(new LostExceptionCauseRule(), code)).containsExactly(13);
    }

    @Test
    void hamcrestAssertThatIsComplete() {
        String code = """
                class CohortTest {
                    @Test
                    void compares() {
                        assertThat(first.compareTo(second), lessThan(0));
                        assertThat("reason", status, is(CREATED));
                        assertThat(first.compareTo(second));
                    }
                }
                """;

        // У Hamcrest и JUnit 4 сравнение - в матчере, вызов с ним проверяет сам
        assertThat(RuleTests.lines(new IncompleteAssertionRule(), code)).containsExactly(6);
    }

    @Test
    void parameterWithFieldNameIsAnotherCollection() {
        String code = """
                class CriteriaBuilder {
                    private final List<String> uuids = new ArrayList<>();
                    CriteriaBuilder addUuids(String... uuids) {
                        for (String uuid : uuids) {
                            this.uuids.add(uuid);
                        }
                        return this;
                    }
                    void duplicate() {
                        for (String uuid : uuids) {
                            uuids.add(uuid + "-copy");
                        }
                    }
                }
                """;

        assertThat(RuleTests.lines(new CollectionModificationInForEachRule(), code)).containsExactly(11);
    }

    @Test
    void ciFlagsAndEnvironmentNamesAreNotSecrets() {
        List<String> lines = new ProjectFixture(dir)
                .write(".github/workflows/build.yaml", """
                        jobs:
                          build:
                            runs-on: ubuntu-latest
                            steps:
                              - uses: actions/checkout@v4
                                with:
                                  persist-credentials: false
                              - uses: actions/setup-java@v4
                                with:
                                  server-password: MAVEN_REPO_API_KEY
                                env:
                                  DB_PASSWORD: s3cr3t-Pa55
                        """)
                .lines(new CiSecretInVariablesRule());

        assertThat(lines).containsExactly("build.yaml:12");
    }

    @Test
    void feignClientBodyIsOutgoing() {
        Map<String, String> sources = Map.of(
                "IskraClient.java", """
                        @FeignClient(name = "iskra", url = "${iskra.url}")
                        interface IskraClient {
                            @PostMapping("/generate")
                            void generate(@RequestBody GenerateRequest request);
                        }
                        """,
                "GenerateRequest.java", "class GenerateRequest { @NotBlank private String table; }");

        // Тело запроса, который приложение само отправляет, проверяет тот, кто его принимает
        assertThat(RuleTests.checkProject(new RequestBodyWithoutValidRule(), sources)).isEmpty();
    }

    @Test
    void heartbeatRunsOnEveryInstance() {
        String code = """
                class HeartbeatTaskScheduler {
                    @Scheduled(fixedDelay = 30000)
                    public void updateHeartbeat() {
                        batchTaskService.updateHeartbeat();
                    }
                }
                """;

        assertThat(RuleTests.check(new ScheduledWithoutLockRule(), code)).isEmpty();
    }

    @Test
    void junit4MessageGoesFirst() {
        String code = """
                import org.junit.Assert;
                class BarriersTest {
                    @Test
                    void counts() {
                        Assert.assertEquals("wrong map.size on iteration = " + i, 1, map.size());
                        Assert.assertTrue("metric was not sent", invocations >= 1);
                        Assert.assertEquals(map.size(), 1);
                        Assert.assertTrue("always", true);
                    }
                }
                """;

        // Строка первым аргументом - сообщение: ожидаемое и условие идут за ней
        assertThat(RuleTests.lines(new AssertEqualsArgumentOrderRule(), code)).containsExactly(7);
        assertThat(RuleTests.lines(new ConstantAssertionRule(), code)).containsExactly(8);
    }

    @Test
    void castAfterClassCheckOrInTypedStrategyIsSafe() {
        String code = """
                class BooleanCellConfigurer implements CellConfigurer {
                    public void configure(Cell cell, Object object) {
                        cell.setCellValue((Boolean) object);
                    }
                    public Class<Boolean> getType() {
                        return Boolean.class;
                    }
                }
                class Entity {
                    public boolean equals(Object object) {
                        if (Hibernate.getClass(object) != Hibernate.getClass(this)) {
                            return false;
                        }
                        Entity other = (Entity) object;
                        return id.equals(other.id);
                    }
                }
                class Reader {
                    String read(Object value) {
                        return (String) value;
                    }
                }
                """;

        assertThat(RuleTests.lines(new CastWithoutInstanceofRule(), code)).containsExactly(20);
    }

    @Test
    void methodReferenceUsedByStreamDoesNotEscape() {
        String code = """
                class Cohort {
                    private final Set<Integer> members = new HashSet<>();
                    Cohort(Integer[] ids, EventBus bus) {
                        Arrays.stream(ids).forEach(this::addMember);
                        boolean any = members.stream().anyMatch(this::isActive);
                        bus.subscribe(this::onEvent);
                    }
                }
                """;

        // Стрим вызывает функцию сразу в этом потоке; подписка хранит ее и вызовет из другого
        assertThat(RuleTests.lines(new ThisEscapeInConstructorRule(), code)).containsExactly(6);
    }

    @Test
    void booleanFromRequiredPropertyIsNotNull() {
        String code = """
                @Configuration
                class Redis {
                    @Value("${spring.redis.cluster-on}")
                    private Boolean isCluster;
                    @Value("${spring.redis.sentinel:}")
                    private Boolean sentinel;
                    void configure() {
                        if (isCluster) {
                            cluster();
                        }
                        if (sentinel) {
                            sentinel();
                        }
                    }
                }
                """;

        // Без свойства приложение не запустится; пустое значение по умолчанию дает null
        assertThat(RuleTests.lines(new NullUnboxingRule(), code)).containsExactly(11);
    }

    @Test
    void collectionFilledInStaticInitializerIsSafe() {
        String code = """
                @Component
                class SchemaColumnMapper {
                    private static final Map<String, Kind> KINDS = new HashMap<>();
                    private final Map<String, Kind> custom = new HashMap<>();
                    static {
                        register(Kind.DATE, "date");
                    }
                    private static void register(Kind kind, String... names) {
                        for (String name : names) {
                            KINDS.put(name, kind);
                        }
                    }
                    public void addCustom(String name, Kind kind) {
                        custom.put(name, kind);
                    }
                }
                """;

        // static-блок выполняется при загрузке класса, до того как коллекцию увидит другой поток
        assertThat(RuleTests.lines(new NonThreadSafeCollectionInBeanRule(), code)).containsExactly(4);
    }

    @Test
    void cleanupOfExpiredDataIsSafeOnEveryInstance() {
        String code = """
                class IdempotencyCleanupJob {
                    @Scheduled(fixedDelay = 3600000)
                    public void deleteExpired() {
                        store.deleteExpired();
                    }
                    @Scheduled(fixedDelay = 3600000)
                    public void sendReport() {
                        reportService.send();
                    }
                }
                """;

        assertThat(RuleTests.lines(new ScheduledWithoutLockRule(), code)).containsExactly(6);
    }

    @Test
    void recognizedExceptionIsLoggedWithoutStack() {
        String code = """
                class Loader {
                    void load(Domain domain) {
                        try {
                            repository.save(domain);
                        } catch (DataIntegrityViolationException ex) {
                            if (ex.getCause() instanceof PSQLException psql && UNIQUE.equals(psql.getSQLState())) {
                                log.warn("Domain already exists, not an error: {}", ex.getLocalizedMessage());
                            } else {
                                log.error("Can't save domain: {}", ex.getMessage());
                            }
                        }
                    }
                }
                """;

        assertThat(RuleTests.lines(new LostStackTraceInLogRule(), code)).containsExactly(9);
    }
}
