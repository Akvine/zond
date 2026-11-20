package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.loaders.FileSystemConfigLoader;
import ru.akvine.zond.loaders.FileSystemTextFileLoader;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.RepositoryCollectionParameterRule;
import ru.akvine.zond.rules.codesmell.TestDependencyInMainScopeRule;
import ru.akvine.zond.rules.concurrency.AsyncWithoutExecutorRule;
import ru.akvine.zond.rules.concurrency.IgnoredSubmitResultRule;
import ru.akvine.zond.rules.concurrency.ScheduledWithoutLockRule;
import ru.akvine.zond.rules.concurrency.WaitWithoutTimeoutRule;
import ru.akvine.zond.rules.logical.AssertionOnlyInCatchRule;
import ru.akvine.zond.rules.logical.BigDecimalMisuseRule;
import ru.akvine.zond.rules.logical.CastWithoutInstanceofRule;
import ru.akvine.zond.rules.logical.CircularDependencyRule;
import ru.akvine.zond.rules.logical.DefaultCharsetRule;
import ru.akvine.zond.rules.logical.EntityFieldWithoutColumnRule;
import ru.akvine.zond.rules.logical.IndexOutOfBoundsRule;
import ru.akvine.zond.rules.logical.LazyAccessOutsideTransactionRule;
import ru.akvine.zond.rules.logical.ListGetFirstWithoutCheckRule;
import ru.akvine.zond.rules.logical.LogPlaceholderMismatchRule;
import ru.akvine.zond.rules.logical.MissingConfigPropertyRule;
import ru.akvine.zond.rules.logical.NullArgumentRule;
import ru.akvine.zond.rules.logical.NullInsteadOfEmptyCollectionRule;
import ru.akvine.zond.rules.logical.OptionalMisuseRule;
import ru.akvine.zond.rules.logical.PaginationWithoutSortRule;
import ru.akvine.zond.rules.logical.SqlDestructiveStatementRule;
import ru.akvine.zond.rules.logical.SqlTableWithoutPrimaryKeyRule;
import ru.akvine.zond.rules.performance.BlockingCallInReactiveRule;
import ru.akvine.zond.rules.performance.HiddenReflectionInHotMethodRule;
import ru.akvine.zond.rules.performance.HttpClientPerCallRule;
import ru.akvine.zond.rules.performance.LoggingInLoopRule;
import ru.akvine.zond.rules.performance.RepositoryCallInLoopRule;
import ru.akvine.zond.rules.security.ExceptionMessageToClientRule;
import ru.akvine.zond.rules.security.HeaderInjectionRule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ложные срабатывания, найденные выборочной проверкой находок на 73 проектах: в каждом тесте рядом стоят код,
 * на котором правило срабатывало зря, и код, где находка обязана остаться
 */
class CorpusAuditRulesTest {

    @Test
    void assertionInCatchIsFineWhenTryAssertsToo() {
        assertThat(RuleTests.lines(new AssertionOnlyInCatchRule(), """
                class TransitionTest {
                    @Test
                    void eitherOutcomeIsChecked() {
                        try {
                            service.run(id);
                            assertThat(state()).isEqualTo(DONE);
                        } catch (IllegalStateException e) {
                            assertThat(state()).isEqualTo(CREATED);
                        }
                    }
                    @Test
                    void onlyFailureIsChecked() {
                        try {
                            service.run(id);
                        } catch (IllegalStateException e) {
                            assertThat(state()).isEqualTo(CREATED);
                        }
                    }
                }
                """)).containsExactly(15);
    }

    @Test
    void pageWithoutSortIsJudgedByWhereItGoes() {
        List<Violation> violations = RuleTests.check(new PaginationWithoutSortRule(), """
                class Pages {
                    private OrderRepository orderRepository;
                    Page<Order> sortedLater(int page, int size) {
                        return orderRepository.findAll(PageRequest.of(page, size).withSort(Sort.by("id")));
                    }
                    Page<Order> toRepository(int page, int size) {
                        return orderRepository.findAll(PageRequest.of(page, size));
                    }
                    Pageable toCaller(int page, int size) {
                        return PageRequest.of(page, size);
                    }
                }
                """);

        // Страница, ушедшая в репозиторий без порядка, - улика; что сделают с возвращенной, отсюда не видно
        assertThat(violations).extracting(violation -> violation.line() + " " + violation.confidenceOrDefault())
                .containsExactly("7 " + Confidence.PROBABLE, "10 " + Confidence.SUSPICION);
    }

    @Test
    void indexFromSearchRespectsGuardsAroundSearch() {
        assertThat(RuleTests.lines(new IndexOutOfBoundsRule(), """
                class Names {
                    String withoutQuery(String url) {
                        return url.substring(0, url.contains("?") ? url.indexOf("?") : url.length());
                    }
                    String rawType(String name) {
                        if (isGeneric(name)) {
                            return name.substring(0, name.indexOf("<"));
                        }
                        return name;
                    }
                    private boolean isGeneric(String name) {
                        return name.contains("<");
                    }
                    String extension(String file) {
                        return file.substring(file.lastIndexOf('.'));
                    }
                    int sum(int[] values) {
                        int[] weights = new int[17];
                        int sum = 0;
                        for (int i = 0; i < values.length; i++) {
                            sum += values[i] * weights[i];
                        }
                        return sum;
                    }
                }
                """)).containsExactly(15);
    }

    @Test
    void optionalFoundByTheSameConditionIsPresent() {
        assertThat(RuleTests.lines(new OptionalMisuseRule(), """
                class Organizations {
                    String identifier(List<Organization> all, String code) {
                        boolean exists = all.stream().anyMatch(organization -> organization.getCode().equals(code));
                        if (exists) {
                            return all.stream().filter(it -> it.getCode().equals(code)).findFirst().get().getId();
                        }
                        return all.stream().filter(it -> it.isDefault()).findFirst().get().getId();
                    }
                }
                """)).containsExactly(7);
    }

    @Test
    void asyncWithoutNameIsFineWhenDefaultExecutorIsConfigured(@TempDir Path dir) throws IOException {
        String mailer = """
                @Service
                class Mailer {
                    @Async
                    public void send(String to) { }
                }
                """;
        assertThat(RuleTests.lines(new AsyncWithoutExecutorRule(), mailer)).containsExactly(3);

        Map<String, String> configurer = new LinkedHashMap<>();
        configurer.put("Mailer.java", mailer);
        configurer.put("AsyncConfig.java", """
                @Configuration
                class AsyncConfig implements AsyncConfigurer {
                    @Override
                    public Executor getAsyncExecutor() { return pool(); }
                }
                """);
        assertThat(new AsyncWithoutExecutorRule().checkContext(context(dir, configurer))).isEmpty();

        Map<String, String> bean = new LinkedHashMap<>();
        bean.put("Mailer.java", mailer);
        bean.put("Pools.java", """
                @Configuration
                class Pools {
                    @Bean
                    ThreadPoolTaskExecutor mailPool() { return new ThreadPoolTaskExecutor(); }
                }
                """);
        assertThat(new AsyncWithoutExecutorRule().checkContext(context(dir, bean))).isEmpty();

        // Два пула и ни один не назван taskExecutor: какой из них по умолчанию, Spring не решит
        bean.put("MorePools.java", """
                @Configuration
                class MorePools {
                    @Bean
                    ThreadPoolTaskExecutor reportPool() { return new ThreadPoolTaskExecutor(); }
                }
                """);
        assertThat(new AsyncWithoutExecutorRule().checkContext(context(dir, bean))).hasSize(1);

        Files.writeString(dir.resolve("application.properties"), "spring.task.execution.pool.max-size=16\n");
        assertThat(new AsyncWithoutExecutorRule().checkContext(context(dir, Map.of("Mailer.java", mailer)))).isEmpty();
    }

    @Test
    void scheduledTaskGuardedByLockCall() {
        assertThat(RuleTests.lines(new ScheduledWithoutLockRule(), """
                @Component
                class Jobs {
                    private OrderRepository orderRepository;
                    private LockProvider lockProvider;
                    private LeaderService leaderService;
                    @Scheduled(fixedDelay = 1000)
                    public void underLock() {
                        lockProvider.lock("orders", () -> orderRepository.saveAll(load()));
                    }
                    @Scheduled(fixedDelay = 1000)
                    public void onLeaderOnly() {
                        if (!leaderService.isLeader()) {
                            return;
                        }
                        orderRepository.saveAll(load());
                    }
                    @Scheduled(fixedDelay = 1000)
                    public void unguarded() {
                        orderRepository.saveAll(load());
                    }
                }
                """)).containsExactly(17);
    }

    @Test
    void waitOnCompletedFutureDoesNotBlock() {
        assertThat(RuleTests.lines(new WaitWithoutTimeoutRule(), """
                class Tasks {
                    private ExecutorService executor;
                    private CompletionService<Report> completionService;
                    Report next() throws Exception {
                        Future<Report> done = completionService.take();
                        return done.get();
                    }
                    void all(List<Callable<Report>> tasks) throws Exception {
                        List<Future<Report>> results = executor.invokeAll(tasks, 10, TimeUnit.SECONDS);
                        for (Future<Report> result : results) {
                            use(result.get());
                        }
                    }
                    Report twice(CompletableFuture<Report> profile) {
                        use(profile.join());
                        return profile.join();
                    }
                    Report pending(Future<Report> future) throws Exception {
                        return future.get();
                    }
                }
                """)).containsExactly(15, 19);
    }

    @Test
    void submittedTaskThatHandlesItsOwnErrors() {
        assertThat(RuleTests.lines(new IgnoredSubmitResultRule(), """
                class Sender {
                    private ExecutorService executorService;
                    void start() {
                        executorService.submit(() -> send("host"));
                        executorService.submit(this::watch);
                        executorService.submit(() -> risky("host"));
                    }
                    private void send(String host) {
                        try {
                            client.post(host);
                        } catch (Exception e) {
                            log.warn("Не отправлено", e);
                        }
                    }
                    private void watch() {
                        while (!stopped) {
                            try {
                                poll();
                            } catch (Exception e) {
                                log.warn("Сбой", e);
                            }
                        }
                    }
                    private void risky(String host) {
                        client.post(host);
                    }
                }
                """)).containsExactly(6);
    }

    @Test
    void firstElementInsideMatchingCatch() {
        assertThat(RuleTests.lines(new ListGetFirstWithoutCheckRule(), """
                class Settings {
                    String header(List<String> loaded) {
                        try {
                            return loaded.get(0);
                        } catch (NullPointerException | IndexOutOfBoundsException ex) {
                            return "default";
                        }
                    }
                    String first(List<String> loaded) {
                        return loaded.get(0);
                    }
                }
                """)).containsExactly(10);
    }

    @Test
    void clientBuiltInFactoryMethodLivesWithItsResult() {
        assertThat(RuleTests.lines(new HttpClientPerCallRule(), """
                class Notifiers {
                    RestTemplate notifierTemplate(Proxy proxy) {
                        RestTemplate template = new RestTemplate();
                        HttpClientBuilder builder = HttpClientBuilder.create();
                        template.setRequestFactory(new HttpComponentsClientHttpRequestFactory(builder.build()));
                        return template;
                    }
                    String load(String url) {
                        RestTemplate template = new RestTemplate();
                        return template.getForObject(url, String.class);
                    }
                }
                """)).containsExactly(9);
    }

    @Test
    void exactDoubleLiteralHasNoBinaryError() {
        assertThat(RuleTests.lines(new BigDecimalMisuseRule(), """
                class Prices {
                    BigDecimal zero = new BigDecimal(0.00);
                    BigDecimal guitar = new BigDecimal(1299.0);
                    BigDecimal half = new BigDecimal(0.5);
                    BigDecimal tenth = new BigDecimal(0.1);
                    BigDecimal of(double ratio) { return new BigDecimal(ratio); }
                }
                """)).containsExactly(5, 6);
    }

    @Test
    void messageOfOwnExceptionIsMeantForClient() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("ShopException.java", "class ShopException extends RuntimeException { }");
        files.put("Errors.java", """
                @RestControllerAdvice
                class Errors {
                    @ExceptionHandler(ShopException.class)
                    Result business(Exception e) {
                        return Result.failed(e.getMessage());
                    }
                    @ExceptionHandler({SQLException.class, IOException.class})
                    Result technical(Exception e) {
                        return Result.failed(e.getMessage());
                    }
                    @ExceptionHandler
                    Result any(Exception e) {
                        if (e instanceof ShopException) {
                            return Result.failed(e.getMessage());
                        }
                        return Result.failed(e.getLocalizedMessage());
                    }
                }
                """);
        List<SourceFile> project = RuleTests.project(files);

        assertThat(new ExceptionMessageToClientRule().check(project.get(1)))
                .extracting(Violation::line).containsExactly(9, 16);
    }

    @Test
    void publisherPassedToWebFluxResponseDoesNotBlock() {
        assertThat(RuleTests.lines(new BlockingCallInReactiveRule(), """
                import reactor.core.publisher.Mono;
                class PostHandler {
                    private PostRepository posts;
                    private RestTemplate restTemplate;
                    Mono<ServerResponse> delete(ServerRequest request) {
                        return ServerResponse.noContent().build(posts.deleteById(request.pathVariable("id")));
                    }
                    Mono<String> locale(ServerRequest request) {
                        return Mono.just(request).map(current -> resolver.resolve(current.exchange()).toString());
                    }
                    Mono<String> blocking(String url) {
                        return Mono.just(url).map(address -> restTemplate.getForObject(address, String.class));
                    }
                }
                """)).containsExactly(12);
    }

    @Test
    void expressionCheckedByLibraryPredicateIsNotNull() {
        assertThat(RuleTests.lines(new NullArgumentRule(), """
                class Listener {
                    void handle(Event event) {
                        if (Objects.nonNull(event.getMessage())) {
                            finish(event.getMessage());
                        }
                    }
                    void handleUnchecked(Event event) {
                        finish(event.getMessage());
                    }
                    private void finish(Message message) {
                        message.close();
                    }
                }
                class Event {
                    private Message message;
                    private boolean ready;
                    Message getMessage() {
                        return ready ? message : null;
                    }
                }
                """)).containsExactly(8);
    }

    @Test
    void testLibraryNeededByMainCodeOrOnlyAsPlatform(@TempDir Path dir) throws IOException {
        write(dir, "test-util/build.gradle", """
                dependencies {
                    implementation platform("org.junit:junit-bom:6.0.0")
                    implementation "org.testcontainers:postgresql:1.20.4"
                    implementation "org.mockito:mockito-core:5.14.2"
                }
                """);
        write(dir, "app/build.gradle", """
                dependencies {
                    implementation "org.testcontainers:postgresql:1.20.4"
                }
                """);
        Map<String, String> files = new LinkedHashMap<>();
        files.put(dir.resolve("test-util/src/main/java/util/Database.java").toString(), """
                package util;
                import org.testcontainers.containers.PostgreSQLContainer;
                public class Database {
                    public PostgreSQLContainer<?> start() {
                        return new PostgreSQLContainer<>("postgres:16");
                    }
                }
                """);
        files.put(dir.resolve("app/src/main/java/app/Application.java").toString(), """
                package app;
                import org.testcontainers.shaded.org.apache.commons.lang3.StringUtils;
                public class Application {
                    public static void main(String[] args) {
                        System.out.println(StringUtils.trim(args[0]));
                    }
                }
                """);
        ScanContext context = context(dir, files);

        // Платформа задает только версии; Testcontainers нужен основному коду модуля утилит. Mockito там же
        // основному коду не нужен, а в приложении тестовой библиотеке не место, даже если на нее есть импорт
        assertThat(new TestDependencyInMainScopeRule().checkContext(context))
                .extracting(violation -> dir.relativize(violation.file()).toString().replace('\\', '/') + ":"
                        + violation.line())
                .containsExactlyInAnyOrder("test-util/build.gradle:4", "app/build.gradle:2");
    }

    @Test
    void rowsToWriteAndQueryArgumentsAreNotValuesForInClause() {
        // У строк для записи и у позиционных аргументов запроса порядок и повторы значимы
        assertThat(RuleTests.lines(new RepositoryCollectionParameterRule(), """
                interface CardRepository extends JpaRepository<Card, Long> {
                    void batchCreate(List<Card> cards);
                    int insertList(@Param("list") List<Relation> relations);
                    List<Card> query(String sql, List<Object> arguments);
                    List<Card> findByOwnerIn(List<Owner> owners);
                    int updateStatusByOwnerIn(Status status, List<Owner> owners);
                    @Query("update Card c set c.active = false where c.owner in :owners")
                    int updateActive(List<Owner> owners);
                    int updateStatus(List<Long> ids, Status status);
                }
                """)).containsExactly(5, 6, 8, 9);
    }

    @Test
    void nullAsPrivateSignalCheckedByEveryCaller() {
        // Закрытый метод отдает null как знак «пропустить», и каждый, кто его вызывает, это проверяет
        assertThat(RuleTests.lines(new NullInsteadOfEmptyCollectionRule(), """
                class Head {
                    List<Tag> tags(List<Map<String, String>> metas) {
                        return metas.stream().map(Head::sanitize).filter(Objects::nonNull).map(Tag::new).toList();
                    }
                    private static Map<String, String> sanitize(Map<String, String> attributes) {
                        if (attributes.isEmpty()) {
                            return null;
                        }
                        return new LinkedHashMap<>(attributes);
                    }
                    void print(String key) {
                        List<String> rows = load(key);
                        if (rows == null) {
                            return;
                        }
                        rows.forEach(System.out::println);
                    }
                    private List<String> load(String key) {
                        if (key.isEmpty()) {
                            return null;
                        }
                        return List.of(key);
                    }
                    int count(String key) {
                        return find(key).size();
                    }
                    private List<String> find(String key) {
                        if (key.isEmpty()) {
                            return null;
                        }
                        return List.of(key);
                    }
                    public List<String> open(String key) {
                        if (key.isEmpty()) {
                            return null;
                        }
                        return List.of(key);
                    }
                    boolean has(String key) {
                        return open(key) != null;
                    }
                }
                """)).containsExactly(29, 35);
    }

    @Test
    void valueThatChangesOnEveryCallIsNotRememberedAsChecked() {
        // poll() каждый раз отдает новое значение: проверка одного вызова ничего не говорит о следующем.
        // peek() и геттер при повторе отдают то же самое
        assertThat(RuleTests.lines(new NullArgumentRule(), """
                class Worker {
                    void drain(Source source) {
                        if (Objects.nonNull(source.poll())) {
                            finish(source.poll());
                        }
                        if (source.poll() != null) {
                            finish(source.poll());
                        }
                        if (Objects.nonNull(source.peek())) {
                            finish(source.peek());
                        }
                        if (source.peek() != null) {
                            finish(source.peek());
                        }
                    }
                    private void finish(Message message) {
                        message.close();
                    }
                }
                class Source {
                    private Message head;
                    private boolean ready;
                    Message poll() {
                        return ready ? head : null;
                    }
                    Message peek() {
                        return ready ? head : null;
                    }
                }
                """)).containsExactly(4, 7);
    }

    @Test
    void schemaScriptsAndKeys(@TempDir Path dir) throws IOException {
        write(dir, "db/schema.sql", """
                DROP TABLE vets IF EXISTS;
                CREATE TABLE vets (id INTEGER PRIMARY KEY, name VARCHAR(30));
                DROP TABLE IF EXISTS owners;
                CREATE TABLE owners (id INTEGER PRIMARY KEY);
                DROP TABLE archive;
                CREATE TABLE db_lock (lock_id VARCHAR(200) NOT NULL, process_id VARCHAR(36) NOT NULL);
                CREATE UNIQUE INDEX db_lock_id ON db_lock (lock_id);
                CREATE TABLE notes (title VARCHAR(200), body VARCHAR(2000));
                CREATE UNIQUE INDEX notes_title ON notes (title);
                """);
        ScanContext context = context(dir, Map.of());

        // Пересоздание таблицы в том же скрипте - не потеря данных; уникальный индекс на обязательной колонке - ключ
        assertThat(new SqlDestructiveStatementRule().checkContext(context))
                .extracting(Violation::line).containsExactly(5);
        assertThat(new SqlTableWithoutPrimaryKeyRule().checkContext(context))
                .extracting(Violation::line).containsExactly(8);
    }

    @Test
    void tableDefinedTwiceWithDifferentColumnsIsNotCompared(@TempDir Path dir) throws IOException {
        write(dir, "sql/configdb.sql", """
                DROP TABLE IF EXISTS server_config;
                CREATE TABLE server_config (id INT PRIMARY KEY, cluster VARCHAR(32), value VARCHAR(100));
                CREATE TABLE app (id INT PRIMARY KEY, name VARCHAR(32));
                """);
        write(dir, "sql/portaldb.sql", """
                DROP TABLE IF EXISTS server_config;
                CREATE TABLE server_config (id INT PRIMARY KEY, value VARCHAR(100));
                """);
        Map<String, String> files = new LinkedHashMap<>();
        files.put("ServerConfig.java", """
                @Entity
                @Table(name = "server_config")
                class ServerConfig {
                    @Id
                    private Long id;
                    private String cluster;
                    private String value;
                }
                """);
        files.put("App.java", """
                @Entity
                @Table(name = "app")
                class App {
                    @Id
                    private Long id;
                    private String title;
                }
                """);

        // server_config описана дважды и по-разному: к какой из двух баз относится сущность, по скриптам не видно
        assertThat(new EntityFieldWithoutColumnRule().checkContext(context(dir, files)))
                .extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("App.java:6");
    }

    @Test
    void lazyAccessNeedsOpenSessionToBeOff(@TempDir Path dir) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Order.java", """
                @Entity
                class Order {
                    @OneToMany(mappedBy = "order")
                    private List<Item> items;
                }
                """);
        files.put("OrderController.java", """
                @RestController
                class OrderController {
                    private OrderService orderService;
                }
                """);
        files.put("OrderService.java", """
                @Service
                class OrderService {
                    private OrderRepository orderRepository;
                    public int count(Long id) {
                        Order order = orderRepository.findById(id).orElseThrow();
                        return order.getItems().size();
                    }
                    @Scheduled(fixedDelay = 1000)
                    public void report() {
                        Order order = orderRepository.findById(1L).orElseThrow();
                        log.info("{}", order.getItems().size());
                    }
                }
                """);
        // В веб-приложении сессия по умолчанию открыта до конца запроса; задачи по расписанию она не касается
        assertThat(new LazyAccessOutsideTransactionRule().checkContext(context(dir, files)))
                .extracting(violation -> violation.line() + " " + violation.confidenceOrDefault())
                .containsExactly("6 " + Confidence.SUSPICION, "11 " + Confidence.PROBABLE);

        Files.writeString(dir.resolve("application.properties"), "spring.jpa.open-in-view=false\n");
        assertThat(new LazyAccessOutsideTransactionRule().checkContext(context(dir, files)))
                .extracting(violation -> violation.line() + " " + violation.confidenceOrDefault())
                .containsExactly("6 " + Confidence.PROBABLE, "11 " + Confidence.PROBABLE);
    }

    @Test
    void sameNamedClassesInDifferentPackagesAreDifferentBeans() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("service/MetadataService.java", """
                package app.service;
                public interface MetadataService {
                }
                """);
        files.put("service/MetadataServiceImpl.java", """
                package app.service;
                @Service
                public class MetadataServiceImpl implements MetadataService {
                }
                """);
        files.put("grpc/MetadataServiceImpl.java", """
                package app.grpc;
                import app.service.MetadataService;
                @Service("grpcMetadataService")
                public class MetadataServiceImpl {
                    private final MetadataService service;
                    public MetadataServiceImpl(MetadataService service) { this.service = service; }
                }
                """);
        // Класс из пакета grpc зависит от интерфейса, а реализует его одноименный класс из другого пакета
        assertThat(RuleTests.checkProject(new CircularDependencyRule(), files)).isEmpty();
    }

    @Test
    void exceptionPassedToLogIsRecognizedWithoutItsType() {
        assertThat(RuleTests.lines(new LogPlaceholderMismatchRule(), """
                class Subscriber {
                    void listen(Flux<Post> posts, Throwable t) {
                        posts.subscribe(data -> log.info("found post: {}", data), err -> log.error("error", err));
                        var unwrapped = Exceptions.unwrap(t);
                        log.error("Failed to backup", unwrapped);
                        log.warn("Unexpected error", signal.failure());
                        log.error("Stripe module error", t.getMessage());
                    }
                }
                """)).containsExactly(7);
    }

    @Test
    void headerOfOutgoingRequestIsNotResponseHeader() {
        assertThat(RuleTests.lines(new HeaderInjectionRule(), """
                class Calls {
                    void send(String key, String name) {
                        var request = client.target(url).request().header("Authorization", "key=" + key);
                        HttpRequest.get(url).header("X-Trace", "trace-" + name);
                        headers.add("Authorization", "Bearer " + key);
                    }
                    ResponseEntity<Resource> download(String name) {
                        return ResponseEntity.ok().header("Content-Disposition", "filename=" + name).body(file);
                    }
                }
                """)).containsExactly(8);
    }

    @Test
    void missingPropertyOfConditionalBeanAndOneGivenByDeployment(@TempDir Path dir) throws IOException {
        write(dir, "application.properties", "app.title=Shop\n");
        write(dir, "docker-compose.yml", """
                services:
                  app:
                    image: shop:1.0
                    environment:
                      INSTANCE_ID: node-1
                """);
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Main.java", "@SpringBootApplication class Main { }");
        files.put("Node.java", """
                @Component
                class Node {
                    @Value("${instance-id}")
                    private String instanceId;
                    @Value("${node.region}")
                    private String region;
                }
                """);
        files.put("StandLogging.java", """
                @Configuration
                @Profile("stand")
                class StandLogging {
                    @Value("${syslog.host}")
                    private String host;
                }
                """);
        assertThat(new MissingConfigPropertyRule().checkContext(context(dir, files)))
                .extracting(violation -> violation.file().getFileName() + ":" + violation.line() + " "
                        + violation.confidenceOrDefault())
                .containsExactlyInAnyOrder("Node.java:5 " + Confidence.PROBABLE,
                        "StandLogging.java:4 " + Confidence.SUSPICION);
    }

    @Test
    void defaultCharsetIsUtf8SinceJava18(@TempDir Path dir) throws IOException {
        Map<String, String> files = Map.of("Texts.java", """
                class Texts {
                    byte[] bytes(String text) { return text.getBytes(); }
                    byte[] marker() { return "OK".getBytes(); }
                    byte[] greeting() { return "Привет".getBytes(); }
                }
                """);
        write(dir, "pom.xml", "<project><properties><java.version>17</java.version></properties></project>");
        assertThat(new DefaultCharsetRule().checkContext(context(dir, files)))
                .extracting(Violation::line).containsExactly(2, 4);

        write(dir, "pom.xml", "<project><properties><java.version>21</java.version></properties></project>");
        assertThat(new DefaultCharsetRule().checkContext(context(dir, files))).isEmpty();
    }

    @Test
    void logOncePerBatchOrBeforeLeavingLoop() {
        assertThat(RuleTests.lines(new LoggingInLoopRule(), """
                class Loader {
                    void load() {
                        boolean hasMore = true;
                        while (hasMore) {
                            List<Row> batch = rowRepository.findNext(lastId);
                            lastId = batch.isEmpty() ? lastId : batch.get(batch.size() - 1).getId();
                            hasMore = batch.size() == 500;
                            log.info("Loaded {} rows", batch.size());
                        }
                    }
                    void convert(List<String> files) {
                        for (String file : files) {
                            if (isProtected(file)) {
                                log.info("Нужен пароль: {}", file);
                                throw new IllegalStateException(file);
                            }
                            log.info("Converted {}", file);
                        }
                    }
                    List<Card> loadAll() {
                        List<Card> all = new ArrayList<>();
                        int size;
                        do {
                            List<Card> cards = client.load(cursor);
                            size = cards.size();
                            all.addAll(cards);
                            log.info("Loaded {} cards", size);
                        } while (size >= LIMIT);
                        return all;
                    }
                    void drain(Queue<String> queue) {
                        while (!queue.isEmpty()) {
                            String item = queue.poll();
                            log.info("Handled {}", item);
                        }
                    }
                    void receive(String iterator) {
                        while (iterator != null) {
                            RecordsResponse response = kinesisClient.getRecords(iterator);
                            log.info("Received {} records", response.records().size());
                            iterator = response.nextShardIterator();
                        }
                    }
                    void search(Query query) {
                        int start = 0;
                        long totalHits = 0;
                        do {
                            SearchResult result = search(query, start, 500);
                            totalHits = result.getTotalHits();
                            log.info("Found {} of {}", start, totalHits);
                            start += 500;
                        } while (start < totalHits);
                    }
                    void tokens(StringTokenizer tokenizer) {
                        String token = tokenizer.nextToken();
                        while (token != null) {
                            log.info("Token {}", token);
                            token = tokenizer.hasMoreTokens() ? tokenizer.nextToken() : null;
                        }
                    }
                }
                """)).containsExactly(17, 34, 57);
    }

    @Test
    void castChosenByClassLiteral() {
        assertThat(RuleTests.lines(new CastWithoutInstanceofRule(), """
                class Binder {
                    void bind(Class<?> returnType, Object value) {
                        if (returnType.equals(long.class)) {
                            setLong((Long) value);
                        } else if (returnType == String.class) {
                            setString((String) value);
                        }
                    }
                    void bindAny(Object value) {
                        setLong((Long) value);
                    }
                }
                """)).containsExactly(10);
    }

    @Test
    void sameNamedMethodsOfOtherObjectsAreNotReflection() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Shapes.java", """
                class Shapes {
                    static int declared(ClassOrInterfaceDeclaration type) {
                        return type.getMethods().size();
                    }
                    static int reflected(Object row) {
                        return row.getClass().getMethods().length;
                    }
                    static int inherited(Object row) {
                        return row.getClass().getSuperclass().getDeclaredFields().length;
                    }
                    static int named(String name) throws ClassNotFoundException {
                        return Class.forName(name).getMethods().length;
                    }
                    static int typed(Class<?> type) {
                        return type.getFields().length;
                    }
                }
                """);
        files.put("Report.java", """
                class Report {
                    int count(List<ClassOrInterfaceDeclaration> types, List<Object> rows) throws Exception {
                        int total = 0;
                        for (ClassOrInterfaceDeclaration type : types) {
                            total += Shapes.declared(type);
                        }
                        for (Object row : rows) {
                            total += Shapes.reflected(row) + Shapes.inherited(row) + Shapes.named(row.toString());
                            total += Shapes.typed(row.getClass());
                        }
                        return total;
                    }
                }
                """);
        // type.getMethods() у узла дерева разбора - обычный геттер, а не Class.getMethods(). Класс, полученный
        // вызовом (getSuperclass(), forName(...)), - по-прежнему класс
        assertThat(RuleTests.checkProject(new HiddenReflectionInHotMethodRule(), files))
                .extracting(Violation::line).containsExactly(6, 9, 12, 15);
    }

    @Test
    void repositoryKnownOnlyByNameIsNotConfirmed() {
        assertThat(RuleTests.check(new RepositoryCallInLoopRule(), """
                class Sync {
                    private OrderRepository orderRepository;
                    void byType(List<Long> ids) {
                        for (Long id : ids) {
                            orderRepository.findById(id);
                        }
                    }
                    void byName(List<Long> ids, List<Object> sources) {
                        sources.forEach(repository -> ids.forEach(id -> repository.findById(id)));
                    }
                }
                """)).extracting(violation -> violation.line() + " " + violation.confidenceOrDefault())
                .containsExactly("5 " + Confidence.CONFIRMED, "9 " + Confidence.PROBABLE);
    }

    private ScanContext context(Path dir, Map<String, String> files) {
        return new ScanContext(
                dir,
                RuleTests.project(files),
                new FileSystemConfigLoader().load(dir, file -> true),
                new FileSystemTextFileLoader().load(dir, file -> true));
    }

    private void write(Path dir, String path, String content) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
