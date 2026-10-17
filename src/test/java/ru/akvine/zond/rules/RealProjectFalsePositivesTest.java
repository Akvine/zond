package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.AssertEqualsArgumentOrderRule;
import ru.akvine.zond.rules.codesmell.InternalCollectionExposureRule;
import ru.akvine.zond.rules.codesmell.UnusedClassRule;
import ru.akvine.zond.rules.logical.EntityFieldWithoutColumnRule;
import ru.akvine.zond.rules.codesmell.UnusedConfigPropertyRule;
import ru.akvine.zond.rules.codesmell.UnusedMethodRule;
import ru.akvine.zond.rules.concurrency.LockWithoutFinallyRule;
import ru.akvine.zond.rules.concurrency.MutableStateInSingletonBeanRule;
import ru.akvine.zond.rules.concurrency.NonThreadSafeCollectionInBeanRule;
import ru.akvine.zond.rules.concurrency.ScheduledWithoutLockRule;
import ru.akvine.zond.rules.concurrency.ThisEscapeInConstructorRule;
import ru.akvine.zond.rules.concurrency.UnsynchronizedLazyInitRule;
import ru.akvine.zond.rules.concurrency.VolatileNonAtomicRule;
import ru.akvine.zond.rules.exceptions.BroadCatchRule;
import ru.akvine.zond.rules.exceptions.CatchReturnsDefaultRule;
import ru.akvine.zond.rules.exceptions.EmptyCatchRule;
import ru.akvine.zond.rules.exceptions.LostExceptionCauseRule;
import ru.akvine.zond.rules.exceptions.LostStackTraceInLogRule;
import ru.akvine.zond.rules.exceptions.SingleResultWithoutHandlingRule;
import ru.akvine.zond.rules.exceptions.UnhandledNumberFormatRule;
import ru.akvine.zond.rules.logical.CaseWithoutLocaleRule;
import ru.akvine.zond.rules.logical.CastWithoutInstanceofRule;
import ru.akvine.zond.rules.logical.CollectionModificationInForEachRule;
import ru.akvine.zond.rules.logical.ConstantAssertionRule;
import ru.akvine.zond.rules.logical.ConstantConditionRule;
import ru.akvine.zond.rules.logical.ConstraintOnWrongTypeRule;
import ru.akvine.zond.rules.logical.ConstraintWithoutValidatedRule;
import ru.akvine.zond.rules.logical.IncompleteAssertionRule;
import ru.akvine.zond.rules.logical.IndexWithoutLengthCheckRule;
import ru.akvine.zond.rules.logical.IteratorNextWithoutHasNextRule;
import ru.akvine.zond.rules.logical.MessageBundleMismatchRule;
import ru.akvine.zond.rules.logical.NarrowingCastRule;
import ru.akvine.zond.rules.logical.NullUnboxingRule;
import ru.akvine.zond.rules.logical.NullableDereferenceRule;
import ru.akvine.zond.rules.logical.OptionalMisuseRule;
import ru.akvine.zond.rules.logical.PossibleNullDereferenceRule;
import ru.akvine.zond.rules.logical.ReferenceEqualityRule;
import ru.akvine.zond.rules.logical.SqlChangeWithoutWhereRule;
import ru.akvine.zond.rules.logical.SqlDestructiveStatementRule;
import ru.akvine.zond.rules.logical.TransactionalOnFinalRule;
import ru.akvine.zond.rules.logical.TransactionalSelfInvocationRule;
import ru.akvine.zond.rules.logical.UpdateWithoutWhereRule;
import ru.akvine.zond.rules.performance.ListContainsInLoopRule;
import ru.akvine.zond.rules.performance.ListShiftInLoopRule;
import ru.akvine.zond.rules.performance.RepositoryCallInLoopRule;
import ru.akvine.zond.rules.resources.UnboundedExecutorRule;
import ru.akvine.zond.rules.security.AuthorizationHeaderLoggingRule;
import ru.akvine.zond.rules.security.CiSecretInVariablesRule;
import ru.akvine.zond.rules.security.PathTraversalRule;
import ru.akvine.zond.rules.security.RequestBodyWithoutValidRule;
import ru.akvine.zond.rules.security.SecretInConfigRule;
import ru.akvine.zond.rules.security.SsrfRule;
import ru.akvine.zond.rules.streams.ToMapWithoutMergeRule;
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
    void migrationColumnsAreReadLikeLiquibaseDoes() {
        String entity = """
                @Entity
                @Table(name = "PROPERTY_ENTITY")
                class PropertyEntity {
                    @Id
                    @Column(name = "ID")
                    private Long id;
                    @Column(name = "KEY")
                    private String key;
                    @Column(name = "NOTE")
                    private String note;
                    @Column(name = "STATE")
                    private String state;
                    @Column(name = "SELECTED")
                    private Boolean selected;
                    @Column(name = "MISSING")
                    private String missing;
                }
                """;
        List<String> lines = new ProjectFixture(dir)
                .source("PropertyEntity.java", entity)
                // KEY - колонка, а не индекс MySQL; у ALTER перед следующим changeset нет ';'
                .write("src/main/resources/db/changelog/changelog.sql", """
                        --liquibase formatted sql
                        --changeset akvine:1
                        CREATE TABLE PROPERTY_ENTITY (
                            ID          BIGINT NOT NULL PRIMARY KEY,
                            KEY         VARCHAR(255) NOT NULL
                        );
                        --changeset akvine:2
                        ALTER TABLE PROPERTY_ENTITY ADD NOTE VARCHAR(64)

                        --changeset akvine:3
                        ALTER TABLE PROPERTY_ENTITY ADD STATE VARCHAR(256);
                        """)
                // features/ по пути идет раньше loaders/, но Liquibase применит ее после создания таблицы
                .write("src/main/resources/db/changelog/features/add-selected.xml", """
                        <databaseChangeLog>
                            <changeSet id="2" author="tdm">
                                <addColumn tableName="PROPERTY_ENTITY">
                                    <column name="SELECTED" type="boolean"/>
                                </addColumn>
                            </changeSet>
                        </databaseChangeLog>
                        """)
                .lines(new EntityFieldWithoutColumnRule());

        assertThat(lines).containsExactly("PropertyEntity.java:16");
    }

    @Test
    void singletonFieldWrittenOnStartupIsSafe() {
        String code = """
                @Service
                class DataSourceService implements ApplicationContextAware {
                    private ConnectionPoolStore store;
                    private Metadata metadata;
                    private SortedMap<String, String> variables;
                    private String lastUser;
                    @EventListener(ApplicationReadyEvent.class)
                    public void runAfterStartup() {
                        store = new ConnectionPoolStore();
                    }
                    public void setApplicationContext(ApplicationContext context) {
                        metadata = context.getBean(Metadata.class);
                    }
                    public SortedMap<String, String> getVariables() {
                        if (variables == null) {
                            variables = new TreeMap<>();
                        }
                        return variables;
                    }
                    public void login(String user) {
                        lastUser = user;
                    }
                }
                """;

        // Запуск и Aware-колбэк выполняются один раз; ленивую инициализацию отмечает jr:175
        assertThat(RuleTests.lines(new MutableStateInSingletonBeanRule(), code)).containsExactly(6);
    }

    @Test
    void privateMethodOfTransactionalClassRunsInTransaction() {
        String code = """
                @Transactional
                public class ConceptService {
                    public List<Concept> explode(Concept concept) {
                        return explodeHelper(concept);
                    }
                    private List<Concept> explodeHelper(Concept concept) {
                        return getSets(concept);
                    }
                    @Transactional(readOnly = true)
                    public List<Concept> getSets(Concept concept) {
                        return dao.getSets(concept);
                    }
                }
                @Service
                class OtpService {
                    public int getOneTimePassword(String login) {
                        return (int) getNextOtpNumber(login);
                    }
                    @Transactional
                    public long getNextOtpNumber(String login) {
                        return repository.next(login);
                    }
                }
                """;

        // Закрытый метод вызывается только из открытых методов @Transactional-класса: транзакция уже открыта
        assertThat(RuleTests.lines(new TransactionalSelfInvocationRule(), code)).containsExactly(17);
    }

    @Test
    void comparisonWithBooleanConstantIsSafe() {
        String code = """
                class Flags {
                    boolean check(Boolean sourceOnly, Long price, Long modifiers) {
                        return sourceOnly == Boolean.TRUE && modifiers != price;
                    }
                }
                """;

        assertThat(RuleTests.check(new ReferenceEqualityRule(), code)).extracting(Violation::message)
                .singleElement().asString().contains("modifiers != price");
    }

    @Test
    void collectionOfPrototypeOrNewObjectIsNotShared() {
        String code = """
                @Component
                @Scope(value = ConfigurableBeanFactory.SCOPE_PROTOTYPE)
                class MaskingWriter {
                    private final List<String> errors = new ArrayList<>();
                    public void write(Chunk chunk) {
                        errors.add(chunk.error());
                    }
                }
                @Component
                class RuleSettings {
                    private final Map<String, String> common = new LinkedHashMap<>();
                    static RuleSettings of(Map<String, String> values) {
                        RuleSettings settings = new RuleSettings();
                        settings.common.putAll(values);
                        return settings;
                    }
                }
                @Configuration
                class RemoteConfig {
                    private final Map<String, Object> services = new HashMap<>();
                    private final Map<String, Object> cache = new HashMap<>();
                    @Bean
                    public ServiceManager serviceManager() {
                        banks.forEach(this::initForBank);
                        return new ServiceManager(services);
                    }
                    private void initForBank(String bank) {
                        services.put(bank, new Object());
                    }
                    public void remember(String key) {
                        cache.put(key, key);
                    }
                }
                """;

        // prototype-бин свой у каждого; объект из фабричного метода еще никому не виден; initForBank вызывается
        // только из @Bean (через ссылку на метод)
        assertThat(RuleTests.lines(new NonThreadSafeCollectionInBeanRule(), code)).containsExactly(21);
    }

    @Test
    void resolveOfOtherClassIsNotPathSink() {
        String code = """
                @RestController
                class SchemaController {
                    @PostMapping("/convert")
                    Schema convert(@RequestParam MultipartFile file, @RequestParam String type) {
                        return new Schema(SchemaType.resolve(type, file.getOriginalFilename()));
                    }
                    @PostMapping("/save")
                    void save(@RequestParam MultipartFile file) throws IOException {
                        Path target = baseDir.resolve(file.getOriginalFilename());
                        file.transferTo(target);
                    }
                }
                """;

        // SchemaType.resolve по имени файла определяет тип, а не строит путь
        assertThat(RuleTests.lines(new PathTraversalRule(), code)).containsExactly(9);
    }

    @Test
    void uriParsedForValidationIsNotRequest() {
        String code = """
                @RestController
                class LinkController {
                    @PostMapping("/trim")
                    String trim(@RequestBody TrimRequest request) throws Exception {
                        String link = request.getLink();
                        try {
                            new URI(link);
                        } catch (URISyntaxException exception) {
                            throw new ValidationException("bad link");
                        }
                        return restTemplate.getForObject(link, String.class);
                    }
                }
                """;

        // Разбор адреса ради проверки формата - не обращение по нему; запрос ниже - находка
        assertThat(RuleTests.lines(new SsrfRule(), code)).containsExactly(11);
    }

    @Test
    void oauthRequestObjectIsNotAuthorizationHeader() {
        String code = """
                class SberIdResolver {
                    OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                        OAuth2AuthorizationRequest authorizationRequest = defaultResolver.resolve(request);
                        logger.debug("Auth request=[{}]", authorizationRequest);
                        String authorization = request.getHeader("Authorization");
                        logger.debug("Header=[{}]", authorization);
                        return authorizationRequest;
                    }
                }
                """;

        assertThat(RuleTests.lines(new AuthorizationHeaderLoggingRule(), code)).containsExactly(6);
    }

    @Test
    void javaMigrationUpdatesAllRowsOnPurpose() {
        String code = """
                class MigrateTermsChangeSet implements CustomTaskChange {
                    public void execute(Database database) throws CustomChangeException {
                        connection.prepareStatement("update concept_reference_map set source_code = trim(source_code)").execute();
                    }
                }
                class Cleaner {
                    void clean() {
                        jdbc.update("update orders set archived = true");
                    }
                }
                """;

        assertThat(RuleTests.lines(new UpdateWithoutWhereRule(), code)).containsExactly(8);
    }

    @Test
    void staticMethodOfTransactionalClassIsNotTransactional() {
        String code = """
                @Transactional
                public class AdministrationService {
                    public static List<Class<?>> whitelist() {
                        return List.of();
                    }
                    @Transactional
                    public static void reset() {
                    }
                }
                """;

        assertThat(RuleTests.lines(new TransactionalOnFinalRule(), code)).containsExactly(6);
    }

    @Test
    void repositoryCallThatEndsLoopOrWaitsIsNotNPlusOne() {
        Map<String, String> sources = Map.of(
                "TaskService.java", """
                        @Service
                        class TaskService {
                            private final TaskRepository taskRepository;
                            void restart(List<Task> claimed) {
                                for (Task task : claimed) {
                                    if (task.isRestartable()) {
                                        taskRepository.markRestarted(task.getId());
                                        return;
                                    }
                                }
                            }
                            void lock(String id) throws InterruptedException {
                                while (true) {
                                    if (taskRepository.tryLock(id)) {
                                        return;
                                    }
                                    Thread.sleep(100);
                                }
                            }
                            void unlockAll(List<Task> tasks) {
                                for (Task task : tasks) {
                                    taskRepository.save(task.unlock());
                                }
                            }
                        }
                        """,
                "TaskRepository.java", "interface TaskRepository extends JpaRepository<Task, Long> {}");

        // После markRestarted цикл кончается; while (true) с паузой - ожидание замка, а не перебор данных
        assertThat(RuleTests.checkProject(new RepositoryCallInLoopRule(), sources)).extracting(Violation::line)
                .containsExactly(22);
    }

    @Test
    void recognizedOrImpossibleExceptionNeedsNoCause() {
        String code = """
                class Gateway {
                    void login(String user) {
                        try {
                            api.login(user);
                        } catch (ApiException e) {
                            if (e.getStatus() == 400) {
                                throw new InvalidCredentialsException("rejected: " + e.getMessage());
                            }
                            throw new GatewayException("login failed");
                        }
                    }
                    public Object clone() {
                        try {
                            return super.clone();
                        } catch (CloneNotSupportedException e) {
                            throw new InternalError("should be cloneable");
                        }
                    }
                }
                """;

        assertThat(RuleTests.lines(new LostExceptionCauseRule(), code)).containsExactly(9);
    }

    @Test
    void missingTranslationFallsBackToBaseBundle(@TempDir Path other) {
        List<Violation> withBase = new MessageBundleMismatchRule().checkContext(new ProjectFixture(dir)
                .write("src/main/resources/messages.properties", "title=OpenMRS\n")
                .write("src/main/resources/messages_ar.properties", "title=OpenMRS\nlegacy.key=x\n")
                .write("src/main/resources/messages_de.properties", "other.key=y\n")
                .context());
        // Переводу без ключа хватит основного файла; в основном нет ключей из переводов - устарели или забыты
        assertThat(withBase).singleElement().satisfies(violation -> {
            assertThat(violation.file().getFileName().toString()).isEqualTo("messages.properties");
            assertThat(violation.errorLevel()).isEqualTo(ErrorLevel.MINOR);
            assertThat(violation.message()).contains("legacy.key").contains("other.key");
        });

        List<Violation> withoutBase = new MessageBundleMismatchRule().checkContext(new ProjectFixture(other)
                .write("src/main/resources/messages_en.properties", "title=Title\n")
                .write("src/main/resources/messages_ru.properties", "title=Заголовок\nview.title=Вид\n")
                .context());
        assertThat(withoutBase).extracting(violation -> violation.file().getFileName().toString())
                .containsExactly("messages_en.properties");
    }

    @Test
    void recreatedTableAndSampleSchemaAreNotDestructiveMigrations() {
        List<String> lines = new ProjectFixture(dir)
                .write("src/main/resources/schema.sql", """
                        DROP TABLE IF EXISTS card_tag;
                        CREATE TABLE card_tag (card_id BIGINT, tag VARCHAR(64));
                        DROP TABLE legacy_cards;
                        """)
                .write("samples/schemas/changelog.sql", "ALTER TABLE employees DROP COLUMN legacy_code;\n")
                .lines(new SqlDestructiveStatementRule());

        // schema.sql пересоздает таблицу при каждом запуске; samples - входные данные, а не миграции проекта
        assertThat(lines).containsExactly("schema.sql:3");
    }

    @Test
    void releasingResourcesOnShutdownMayCatchEverything() {
        String code = """
                class KeepAliveService {
                    public void destroy() {
                        try {
                            scheduler.shutdownNow();
                        } catch (Exception ex) {
                            logger.error("Can't stop scheduler");
                        }
                    }
                    public Location find(String text) {
                        try {
                            return service.find(text);
                        } catch (Exception e) {
                            return null;
                        }
                    }
                }
                """;

        assertThat(RuleTests.lines(new BroadCatchRule(), code)).containsExactly(12);
    }

    @Test
    void nullTiedToConditionOrHasCheckIsKnown() {
        String code = """
                class Reader {
                    @Nullable
                    Token peek() { return index < tokens.size() ? tokens.get(index) : null; }
                    boolean hasNext() { return index < tokens.size(); }
                }
                class Query {
                    void read(List<String> fetches, ResultSet rs, Reader reader) throws SQLException {
                        Map<String, Object> identity = fetches.isEmpty() ? null : new HashMap<>();
                        while (rs.next()) {
                            if (fetches.isEmpty()) {
                                continue;
                            }
                            identity.put("a", 1);
                        }
                        while (reader.hasNext()) {
                            reader.peek().kind();
                        }
                        reader.peek().kind();
                    }
                    void unchecked(List<String> fetches) {
                        Map<String, Object> identity = fetches.isEmpty() ? null : new HashMap<>();
                        identity.put("a", 1);
                    }
                }
                """;

        // null у identity связан с fetches.isEmpty(), а у peek() - с hasNext(): обе проверки стоят выше
        assertThat(RuleTests.checkProject(new PossibleNullDereferenceRule(), Map.of("Query.java", code)))
                .extracting(Violation::line).containsExactlyInAnyOrder(18, 22);
    }

    @Test
    void enumNamesAndShortLiteralListsAreSafe() {
        String code = """
                class Utils {
                    private static final List<String> CONDITIONS = List.of("<", ">", "=");
                    <E extends Enum<E>> Map<String, E> enumMap(Class<E> type) {
                        return Arrays.stream(type.getEnumConstants()).collect(Collectors.toMap(Enum::name, Function.identity()));
                    }
                    Map<String, Weight> byDate(List<Weight> weights) {
                        return weights.stream().collect(Collectors.toMap(weight -> weight.getDate(), Function.identity()));
                    }
                    void validate(List<String> parts, List<String> allowed) {
                        for (String part : parts) {
                            if (!CONDITIONS.contains(part) || !allowed.contains(part)) {
                                throw new IllegalArgumentException(part);
                            }
                        }
                    }
                }
                """;

        // Имена констант различны; список из трех знаков не растет вместе с данными
        assertThat(RuleTests.lines(new ToMapWithoutMergeRule(), code)).containsExactly(7);
        assertThat(RuleTests.check(new ListContainsInLoopRule(), code)).extracting(Violation::message)
                .singleElement().asString().contains("allowed.contains");
    }

    @Test
    void mapValueAfterKeySetOrIdentityCheckIsPresent() {
        String code = """
                class Exporter {
                    void export(Map<String, List<String>> byProfile) {
                        Set<String> profiles = byProfile.keySet();
                        for (String profile : profiles) {
                            List<String> properties = byProfile.get(profile);
                            properties.size();
                        }
                    }
                    void unlock(Map<String, Lock> cache, String id) {
                        Lock lock = cache.get(id);
                        if (lock != this) {
                            throw new IllegalArgumentException("wrong instance");
                        }
                        lock.unlock();
                        Lock other = cache.get(id + "-other");
                        other.unlock();
                    }
                }
                """;

        assertThat(RuleTests.lines(new NullableDereferenceRule(), code)).containsExactly(16);
    }

    @Test
    void optionalGetInsideCatchingTryIsHandled() {
        String code = """
                class Generator {
                    Segment next(Map<String, Segment> line) {
                        try {
                            return line.values().stream().filter(Segment::isFree).findFirst().get();
                        } catch (NoSuchElementException e) {
                            return null;
                        }
                    }
                    Rod find(long id) {
                        return rodService.findById(id).get();
                    }
                }
                """;

        assertThat(RuleTests.lines(new OptionalMisuseRule(), code)).containsExactly(10);
    }

    @Test
    void constraintWithManualCheckIsJustDocumentation() {
        String code = """
                import jakarta.validation.constraints.NotNull;
                @Component
                class GuidUtils {
                    public String toWsFormat(@NotNull String guid) {
                        Assert.notNull(guid, "guid is null");
                        return guid.toUpperCase();
                    }
                    public String toRkFormat(@NotNull String guid) {
                        return guid.toLowerCase();
                    }
                }
                """;

        assertThat(RuleTests.lines(new ConstraintWithoutValidatedRule(), code)).containsExactly(8);
    }

    @Test
    void backfillAndReferenceReloadNeedNoWhere() {
        List<String> lines = new ProjectFixture(dir)
                .write("src/main/resources/db/changelog/users.xml", """
                        <databaseChangeLog>
                            <changeSet id="1" author="openmrs">
                                <addColumn tableName="users">
                                    <column name="person_id" type="int"/>
                                </addColumn>
                                <update tableName="users">
                                    <column name="person_id" valueNumeric="user_id"/>
                                </update>
                            </changeSet>
                        </databaseChangeLog>
                        """)
                .write("src/main/resources/db/changelog/policies.sql", """
                        DELETE FROM public.data_policy;
                        INSERT INTO public.data_policy (id, name) VALUES (1, 'mask');
                        DELETE FROM sessions;
                        """)
                .lines(new SqlChangeWithoutWhereRule());

        // Заполнение только что добавленной колонки и перезагрузка справочника - намеренно все строки
        assertThat(lines).containsExactly("policies.sql:3");
    }

    @Test
    void ignoredBadNumberAndRacyImmutableCacheAreBenign() {
        String code = """
                class Settings {
                    private static String hostname;
                    private static Map<String, String> cache;
                    long keepAlive(String header, long fallback) {
                        try {
                            return Long.parseLong(header) * 1000;
                        } catch (NumberFormatException exception) {
                        }
                        try {
                            return load();
                        } catch (IOException exception) {
                        }
                        return fallback;
                    }
                    static String hostname() throws UnknownHostException {
                        if (hostname == null) {
                            hostname = InetAddress.getLocalHost().getCanonicalHostName();
                        }
                        return hostname;
                    }
                    static Map<String, String> cache() {
                        if (cache == null) {
                            cache = new HashMap<>();
                        }
                        return cache;
                    }
                }
                """;

        assertThat(RuleTests.lines(new EmptyCatchRule(), code)).containsExactly(11);
        // Строку оба потока вычислят одинаково; карту - создадут две разные
        assertThat(RuleTests.lines(new UnsynchronizedLazyInitRule(), code)).containsExactly(22);
    }

    @Test
    void recordAccessorNamedLengthMayBeNull() {
        String code = """
                record Column(String name, Integer length) {
                }
                class Mismatch {
                    boolean longer(Column column, String text, int max) {
                        return column.length() != null && column.length() > max && text.length() != 0;
                    }
                }
                """;

        // length() своей записи - поле Integer, а не длина строки: проверка на null нужна
        assertThat(RuleTests.checkProject(new ConstantConditionRule(), Map.of("Mismatch.java", code))).isEmpty();
    }

    @Test
    void numberFromSettingsAtStartupMayFailFast() {
        String code = """
                @Configuration
                class BotConfig {
                    @Bean
                    BotOptions botOptions(Environment environment) {
                        int port = Integer.parseInt(environment.getProperty("proxy.port"));
                        return new BotOptions(port);
                    }
                }
                @Service
                class Converter {
                    long start(Request request) {
                        return Long.parseLong(request.getStart());
                    }
                }
                """;

        assertThat(RuleTests.lines(new UnhandledNumberFormatRule(), code)).containsExactly(12);
    }

    @Test
    void uuidStringHasKnownLength() {
        String code = """
                class FileStore {
                    String newPath() {
                        String id = UUID.randomUUID().toString().replace("-", "");
                        return id.substring(0, 2) + "/" + id;
                    }
                    boolean isValidInn(String inn) {
                        return inn.substring(0, 9).chars().allMatch(Character::isDigit);
                    }
                }
                """;

        assertThat(RuleTests.lines(new IndexWithoutLengthCheckRule(), code)).containsExactly(7);
    }

    @Test
    void invalidPathIsParseFailure() {
        String code = """
                class SourceTree {
                    Optional<Path> resolve(Path directory, String name) {
                        try {
                            return Optional.of(directory.resolve(name));
                        } catch (InvalidPathException exception) {
                            return Optional.empty();
                        }
                    }
                }
                """;

        assertThat(RuleTests.check(new CatchReturnsDefaultRule(), code)).isEmpty();
    }

    @Test
    void immutableCopyFromConstructorMayBeReturned() {
        String code = """
                class EntityMeta {
                    private final List<ColumnMeta> columns;
                    private final List<String> names;
                    EntityMeta(List<ColumnMeta> columns, List<String> names) {
                        this.columns = List.copyOf(columns);
                        this.names = names;
                    }
                    public List<ColumnMeta> columns() {
                        return columns;
                    }
                    public List<String> names() {
                        return names;
                    }
                }
                """;

        assertThat(RuleTests.lines(new InternalCollectionExposureRule(), code)).containsExactly(11);
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
