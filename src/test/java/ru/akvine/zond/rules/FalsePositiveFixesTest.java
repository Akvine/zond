package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.loaders.FileSystemConfigLoader;
import ru.akvine.zond.loaders.FileSystemTextFileLoader;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ConfigProperty;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.DuplicateStringLiteralRule;
import ru.akvine.zond.rules.codesmell.FieldInjectionRule;
import ru.akvine.zond.rules.codesmell.InternalCollectionExposureRule;
import ru.akvine.zond.rules.codesmell.MagicNumberRule;
import ru.akvine.zond.rules.codesmell.RawTypeRule;
import ru.akvine.zond.rules.codesmell.UnusedConfigPropertyRule;
import ru.akvine.zond.rules.exceptions.BroadCatchRule;
import ru.akvine.zond.rules.exceptions.LostExceptionCauseRule;
import ru.akvine.zond.rules.exceptions.LostStackTraceInLogRule;
import ru.akvine.zond.rules.logical.MoneyInFloatingPointRule;
import ru.akvine.zond.rules.logical.NarrowingCastRule;
import ru.akvine.zond.rules.logical.NestedDtoWithoutValidRule;
import ru.akvine.zond.rules.logical.ReferenceOutsideTransactionRule;
import ru.akvine.zond.rules.logical.SqlBreakingChangeRule;
import ru.akvine.zond.rules.logical.SqlChangeWithoutWhereRule;
import ru.akvine.zond.rules.performance.RepositoryCallInLoopRule;
import ru.akvine.zond.rules.security.LogInjectionRule;
import ru.akvine.zond.rules.security.SecretInConfigRule;
import ru.akvine.zond.rules.streams.ToMapWithoutMergeRule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ложные срабатывания, найденные прогоном на настоящих проектах: каждый тест - случай, который правило
 * раньше отмечало зря, рядом со случаем, который оно отмечать по-прежнему должно
 */
class FalsePositiveFixesTest {
    @TempDir
    Path dir;

    @Test
    void propertyValueContinuedOnNextLinesIsOneProperty() throws IOException {
        write("application.properties", """
                app.list=first\\
                  ,second.key\\
                  ,third.key
                app.path=C:\\\\temp\\\\
                app.name=demo
                """);

        List<ConfigProperty> properties = new FileSystemConfigLoader().load(dir, file -> true).get(0).properties();

        // Строки продолжения - часть значения, а не новые ключи; четное число \\ в конце - не перенос
        assertThat(properties).extracting(ConfigProperty::key).containsExactly("app.list", "app.path", "app.name");
        assertThat(properties.get(0).value()).isEqualTo("first,second.key,third.key");
        assertThat(properties).extracting(ConfigProperty::line).containsExactly(1, 4, 5);
    }

    @Test
    void unusedPropertyIsReportedOnceAndLibrarySectionsAreSuspicion() throws IOException {
        write("src/main/resources/application.properties", """
                app.used=1
                app.unused=2
                app.listed=3
                app.names=app.listed
                lib.cache.size=10
                logger.file.enabled=true
                """);
        write("src/main/resources/application-dev.properties", "app.unused=5\nlib.cache.size=20\n");
        write("src/main/resources/logback-spring.xml", """
                <configuration>
                  <springProperty name="fileEnabled" source="logger.file.enabled"/>
                </configuration>
                """);
        ScanContext context = new ScanContext(
                dir,
                List.of(RuleTests.parse(Path.of("Settings.java"), """
                        class Settings {
                            @Value("${app.used}")
                            int used;
                            @Value("${app.names}")
                            String names;
                        }
                        """)),
                new FileSystemConfigLoader().load(dir, file -> true),
                new FileSystemTextFileLoader().load(dir, file -> true));

        List<Violation> violations = new UnusedConfigPropertyRule().checkContext(context);

        // app.listed назван в значении другого свойства, logger.file.enabled читает logback
        assertThat(violations).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("application.properties:2", "application.properties:5");
        assertThat(violations.get(0).message()).contains("'app.unused'").contains("задано еще в 1 файл(ах)");
        assertThat(violations.get(0).confidence()).isNull();
        // Из раздела lib код не читает ничего: похоже, он принадлежит библиотеке
        assertThat(violations.get(1).confidence()).isEqualTo(Confidence.SUSPICION);
        assertThat(new UnusedConfigPropertyRule().confidence()).isEqualTo(Confidence.PROBABLE);
    }

    @Test
    void templatePlaceholderIsNotSecret() {
        ConfigFile config = new ConfigFile(Path.of("application.properties"), List.of(
                new ConfigProperty("app.client-secret", "{{ .Values.app.clientSecret }}", 1),
                new ConfigProperty("app.api.token", "@api.token@", 2),
                new ConfigProperty("app.db.password", "${DB_PASSWORD}", 3),
                new ConfigProperty("app.mail.password", "Qw3rty!long", 4)));

        assertThat(new SecretInConfigRule().checkConfig(config)).extracting(Violation::line).containsExactly(4);
    }

    @Test
    void fieldsOfUiControllersAreFilledByFramework() {
        assertThat(RuleTests.lines(new FieldInjectionRule(), """
                @UiController("tdm_Plan.edit")
                @UiDescriptor("plan-edit.xml")
                class PlanEdit extends StandardEditor<Plan> {
                    @Autowired
                    private Button saveButton;
                    @Autowired
                    private DataManager dataManager;
                }
                class OldScreen extends AbstractWindow {
                    @Inject
                    private Table table;
                }
                @Service
                class PlanService {
                    @Autowired
                    private PlanRepository repository;
                }
                """)).containsExactly(15);
    }

    @Test
    void collectionWithSetterIsAlreadyOpen() {
        assertThat(RuleTests.lines(new InternalCollectionExposureRule(), """
                class TaskResultDto {
                    private List<String> steps;
                    private final Map<String, String> index = new HashMap<>();
                    public List<String> getSteps() { return steps; }
                    public void setSteps(List<String> steps) { this.steps = steps; }
                    public Map<String, String> getIndex() { return index; }
                }
                """)).containsExactly(6);
    }

    @Test
    void optionalPagingAndErrorPathAreNotPerElementQueries() {
        assertThat(RuleTests.lines(new RepositoryCallInLoopRule(), """
                class Reports {
                    List<String> names(Long clientId, List<Long> ids) {
                        Optional<Setting> settingOptional = settingRepository.findByClientId(clientId);
                        List<Qr> first = settingOptional.map(setting -> qrRepository.findBySettingId(setting.getId())).orElse(List.of());
                        List<Qr> second = settingRepository.findById(clientId).map(setting -> qrRepository.findBySettingId(setting.getId())).orElse(List.of());
                        List<Qr> third = ids.stream().map(id -> qrRepository.findById(id)).toList();
                        return List.of();
                    }
                    void export(int total, int limit, List<Table> tables) {
                        int offset = 0;
                        while (offset <= total) {
                            List<Row> rows = rowRepository.load(offset, limit);
                            offset += limit;
                        }
                        for (int page = 0; page < total; page++) {
                            rowRepository.findAll(PageRequest.of(page, limit));
                        }
                        for (Table table : tables) {
                            try {
                                process(table);
                            } catch (RuntimeException e) {
                                statusRepository.markFailed(table.getId());
                            }
                            statusRepository.markDone(table.getId());
                        }
                    }
                }
                """)).containsExactly(6, 24);
    }

    @Test
    void exceptionKeptInLogOrRethrownIsNotLost() {
        String code = """
                class Client {
                    String call(String url) throws InterruptedException {
                        try {
                            return http.get(url);
                        } catch (IOException e) {
                            log.error("Call failed for {}", url, e);
                            throw new ClientException("Call failed");
                        }
                    }
                    String silent(String url) {
                        try {
                            return http.get(url);
                        } catch (IOException e) {
                            log.error("Call failed: {}", e.getMessage());
                            throw new ClientException("Call failed");
                        }
                    }
                    String wrapped(String url) {
                        try {
                            return http.get(url);
                        } catch (IOException e) {
                            log.error("Call failed: {}", e.getMessage());
                            throw new ClientException("Call failed", e);
                        }
                    }
                    void waitFor() {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            log.warn("Interrupted: {}", e.getMessage());
                            Thread.currentThread().interrupt();
                        }
                    }
                }
                """;

        // Стек записан в лог целиком - причина не потеряна; записан только текст - потеряна
        assertThat(RuleTests.lines(new LostExceptionCauseRule(), code)).containsExactly(15);
        // Исключение уходит дальше причиной нового - стек не теряется; у InterruptedException он ни о чем не говорит
        assertThat(RuleTests.lines(new LostStackTraceInLogRule(), code)).containsExactly(14);
    }

    @Test
    void onlyTextCanForgeLogLines() {
        List<Violation> violations = RuleTests.check(new LogInjectionRule(), """
                @RestController
                class Orders {
                    @GetMapping("/orders/{id}")
                    void find(@PathVariable Long id, @RequestParam String comment, @RequestBody OrderRequest request,
                              @RequestParam UUID token) {
                        log.info("Find order {}", id);
                        log.info("Token {}", token);
                        log.info("Comment {}", comment);
                        log.info("Request {}", request);
                    }
                }
                """);

        // Число и UUID перевода строки не содержат; объект попадет в лог через toString() - это подозрение
        assertThat(violations).extracting(Violation::line).containsExactly(8, 9);
        assertThat(violations).extracting(Violation::confidence).containsExactly(Confidence.CONFIRMED, Confidence.SUSPICION);
    }

    @Test
    void serviceGetByIdIsNotJpaReference() {
        assertThat(RuleTests.lines(new ReferenceOutsideTransactionRule(), """
                class Profiles {
                    void change(Long id) {
                        Client client = clientService.getById(id);
                        String bank = client.getBankId();
                        Client reference = clientRepository.getReferenceById(id);
                        String name = reference.getName();
                    }
                }
                """)).containsExactly(6);
    }

    @Test
    void keysThatCannotRepeatNeedNoMergeFunction() {
        List<Violation> violations = RuleTests.check(new ToMapWithoutMergeRule(), """
                class Sample {
                    void run(Map<String, List<String>> source, List<Order> orders) {
                        Map<String, Integer> sizes = source.entrySet().stream()
                                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().size()));
                        Map<Long, Order> byId = orders.stream().collect(Collectors.toMap(Order::getId, order -> order));
                        Map<String, Order> byName = orders.stream().collect(Collectors.toMap(Order::getName, order -> order));
                    }
                }
                """);

        // Ключи другой Map не повторяются; идентификатор повторится, только если объект попал в поток дважды
        assertThat(violations).extracting(Violation::line).containsExactly(5, 6);
        assertThat(violations).extracting(Violation::confidence).containsExactly(Confidence.SUSPICION, null);
    }

    @Test
    void backfillOfNewColumnNeedsNoWhere() throws IOException {
        write("db/migration/V2__region.sql", """
                alter table orders add region varchar(10);
                update orders set region = 'RU';
                update orders set status = 'NEW';
                alter table orders modify comment null;
                alter table orders modify title varchar(500);
                """);
        ScanContext context = new ScanContext(
                dir, List.of(), List.of(), new FileSystemTextFileLoader().load(dir, file -> true));

        // Новой колонке задают значение во всех строках - для того UPDATE без WHERE и написан
        assertThat(new SqlChangeWithoutWhereRule().checkContext(context)).extracting(Violation::line).containsExactly(3);
        // modify ... null меняет обязательность, а не тип
        assertThat(new SqlBreakingChangeRule().checkContext(context)).extracting(Violation::line).containsExactly(5);
    }

    @Test
    void amountNextToPriceIsQuantity() {
        assertThat(RuleTests.lines(new MoneyInFloatingPointRule(), """
                class Item {
                    private Long price;
                    private Double amount;
                }
                class Payment {
                    private Double amount;
                    private double totalFee;
                }
                """)).containsExactly(6, 7);
    }

    @Test
    void rawTypeMustComeFromJdk() {
        assertThat(RuleTests.lines(new RawTypeRule(), """
                import java.util.Map;
                import org.springframework.amqp.core.*;
                class RabbitConfig {
                    @Bean
                    Queue orders() { return new Queue("orders"); }
                    Map settings() { return null; }
                }
                """)).containsExactly(6);
        assertThat(RuleTests.lines(new RawTypeRule(), """
                import java.util.*;
                class Buffers {
                    Queue pending() { return null; }
                }
                """)).containsExactly(3);
    }

    @Test
    void cleanupInFinallyMayCatchEverything() {
        assertThat(RuleTests.lines(new BroadCatchRule(), """
                class Locks {
                    void run(Lock lock) {
                        try {
                            work();
                        } finally {
                            try {
                                lock.unlock();
                            } catch (Exception e) {
                                log.error("Unlock failed", e);
                            }
                        }
                        try {
                            work();
                        } catch (Exception e) {
                            log.error("Work failed", e);
                        }
                    }
                }
                """)).containsExactly(14);
    }

    @Test
    void clampedValueFitsInt() {
        assertThat(RuleTests.lines(new NarrowingCastRule(), """
                class Sizes {
                    int size(long count, int limit) {
                        int clamped = (int) Math.min(count * 2, limit);
                        return (int) count;
                    }
                }
                """)).containsExactly(4);
    }

    @Test
    void nestedValidationMattersOnlyWhereValidatorGoes() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Api.java", """
                @RestController
                class Api {
                    @PostMapping("/orders")
                    OrderResponse create(@Valid @RequestBody OrderRequest request) { return null; }
                }
                """);
        files.put("OrderRequest.java", """
                class OrderRequest {
                    @NotNull
                    private Address address;
                }
                """);
        files.put("Address.java", "class Address { @NotBlank private String city; private Geo geo; }");
        files.put("Geo.java", "class Geo { @NotNull private Double lat; }");
        files.put("OrderResponse.java", """
                class OrderResponse {
                    @NotNull
                    private Address address;
                }
                """);

        // Ответ никто не проверяет: @Valid в нем ничего не изменит
        assertThat(RuleTests.checkProject(new NestedDtoWithoutValidRule(), files))
                .extracting(violation -> violation.file().toString() + ":" + violation.line())
                .containsExactlyInAnyOrder("OrderRequest.java:2", "Address.java:1");
    }

    @Test
    void repeatedMessagesAreNotDuplicatedConstants() {
        assertThat(RuleTests.lines(new DuplicateStringLiteralRule(), """
                class Validator {
                    void first(Object request) {
                        Assert.notNull(request, "request is null");
                        log.info("request accepted");
                        String type = "application/json";
                    }
                    void second(Object request) {
                        Assert.notNull(request, "request is null");
                        log.info("request accepted");
                        String type = "application/json";
                    }
                    void third(Object request) {
                        if (request == null) {
                            throw new IllegalArgumentException("request is null");
                        }
                        log.info("request accepted");
                        String type = "application/json";
                    }
                }
                """)).containsExactly(5);
    }

    @Test
    void numberNextToUnitIsNamedByIt() {
        assertThat(RuleTests.lines(new MagicNumberRule(), """
                class Timeouts {
                    void run() throws Exception {
                        lock.tryLock(30, TimeUnit.SECONDS);
                        Duration timeout = Duration.ofMinutes(15);
                        cache.expire(key, 45);
                    }
                }
                """)).containsExactly(5);
    }

    private void write(String path, String content) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
