package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.loaders.FileSystemSourceLoader;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.TestWithoutAssertionRule;
import ru.akvine.zond.rules.concurrency.IgnoredSubmitResultRule;
import ru.akvine.zond.rules.concurrency.MutableStateInSingletonBeanRule;
import ru.akvine.zond.rules.exceptions.BroadCatchRule;
import ru.akvine.zond.rules.exceptions.LostExceptionCauseRule;
import ru.akvine.zond.rules.logical.CaughtExceptionInTransactionRule;
import ru.akvine.zond.rules.logical.CircularDependencyRule;
import ru.akvine.zond.rules.logical.CollectionModificationInForEachRule;
import ru.akvine.zond.rules.logical.IndexOfPositiveRule;
import ru.akvine.zond.rules.logical.LiquibaseDuplicateChangeSetRule;
import ru.akvine.zond.rules.logical.ListGetFirstWithoutCheckRule;
import ru.akvine.zond.rules.logical.LogPlaceholderMismatchRule;
import ru.akvine.zond.rules.logical.MissingConfigPropertyRule;
import ru.akvine.zond.rules.logical.ModifyingQueryMisuseRule;
import ru.akvine.zond.rules.logical.MoneyInFloatingPointRule;
import ru.akvine.zond.rules.logical.NullUnboxingRule;
import ru.akvine.zond.rules.logical.PathVariableMismatchRule;
import ru.akvine.zond.rules.logical.PossibleNullDereferenceRule;
import ru.akvine.zond.rules.logical.SqlDestructiveStatementRule;
import ru.akvine.zond.rules.logical.SqlNotNullWithoutDefaultRule;
import ru.akvine.zond.rules.logical.SwitchFallThroughRule;
import ru.akvine.zond.rules.logical.UnknownPropertyInQueryMethodRule;
import ru.akvine.zond.rules.logical.UnstableDependencyVersionRule;
import ru.akvine.zond.rules.performance.RepositoryCallInLoopRule;
import ru.akvine.zond.rules.security.SecretInToStringRule;
import ru.akvine.zond.rules.security.SensitiveDataLoggingRule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ложные срабатывания, найденные на больших проектах (Apache Fineract, ThingsBoard). Каждый случай проверяется
 * рядом с похожим кодом, о котором правило по-прежнему сообщает.
 */
class LargeProjectFalsePositivesTest {
    @TempDir
    Path dir;

    @Test
    void pathVariableNamedByConstant() {
        String code = """
                @RestController
                class ProfileController extends BaseController {
                    private static final String PROFILE_ID = "profileId";
                    private static final String WRONG_ID = "id";
                    @GetMapping("/profile/{profileId}")
                    Profile get(@PathVariable(PROFILE_ID) String strProfileId) { return null; }
                    @GetMapping("/profile/{profileId}/info")
                    Profile info(@PathVariable(WRONG_ID) String strProfileId) { return null; }
                    @GetMapping("/customer/{customerId}")
                    Customer customer(@PathVariable(CUSTOMER_ID) String strCustomerId) { return null; }
                }
                """;

        // Константа своего класса раскрывается; константу родителя по файлу не узнать - параметр не проверяется
        assertThat(RuleTests.lines(new PathVariableMismatchRule(), code)).containsExactly(8);
    }

    @Test
    void ownDaoIsNotSpringDataRepository() {
        Map<String, String> files = Map.of(
                "AgentAppEvent.java", "@Entity class AgentAppEvent { private Long id; private String status; }",
                "Dao.java", "interface Dao<T> { T findById(Long id); }",
                "AgentAppEventDao.java", """
                        interface AgentAppEventDao extends Dao<AgentAppEvent> {
                            List<AgentAppEvent> findByFilter(AgentAppEventFilter filter);
                        }
                        """,
                "AgentAppEventRepository.java", """
                        interface AgentAppEventRepository extends JpaRepository<AgentAppEvent, Long> {
                            List<AgentAppEvent> findByStatus(String status);
                            List<AgentAppEvent> findByStatsu(String status);
                        }
                        """);

        // Имена методов своего Dao Spring не разбирает; у наследника JpaRepository опечатка остается ошибкой
        assertThat(RuleTests.checkProject(new UnknownPropertyInQueryMethodRule(), files))
                .extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("AgentAppEventRepository.java:3");
    }

    @Test
    void changeSetsForDifferentDatabasesMayShareId() {
        List<String> lines = new ProjectFixture(dir)
                .write("src/main/resources/db/changelog/parts/0002_asset.xml", """
                        <databaseChangeLog>
                          <changeSet author="fineract" id="1" context="postgresql">
                            <createTable tableName="asset"><column name="id" type="bigint"/></createTable>
                          </changeSet>
                          <changeSet author="fineract" id="1" context="mysql">
                            <createTable tableName="asset"><column name="id" type="bigint"/></createTable>
                          </changeSet>
                          <changeSet author="fineract" id="2">
                            <addColumn tableName="asset"><column name="code" type="varchar(20)"/></addColumn>
                          </changeSet>
                          <changeSet author="fineract" id="2" context="mysql">
                            <addColumn tableName="asset"><column name="name" type="varchar(20)"/></addColumn>
                          </changeSet>
                        </databaseChangeLog>
                        """)
                .lines(new LiquibaseDuplicateChangeSetRule());

        // На одной базе выполнится один из наборов id="1"; набор id="2" без условия выполнится вместе с любым
        assertThat(lines).containsExactly("0002_asset.xml:11");
    }

    @Test
    void lombokSetterCalledOnlyAtStartupDoesNotMakeStateShared() {
        String code = """
                @Component
                class ActorSystemContext {
                    @Getter
                    @Setter
                    private ActorSystem actorSystem;
                    @Setter
                    private ActorRef appActor;
                }

                @Service
                class DefaultActorService {
                    private final ActorSystemContext actorContext;
                    @PostConstruct
                    public void initActorSystem() {
                        actorContext.setActorSystem(new ActorSystem());
                    }
                    public void restart() {
                        actorContext.setAppActor(null);
                    }
                }

                @Component
                class RequestsExecutor {
                    private ExecutorService executor;
                    @PostConstruct
                    private void init() {
                        executor = Executors.newFixedThreadPool(4);
                    }
                    @PreDestroy
                    private void destroy() {
                        executor.shutdown();
                        executor = null;
                    }
                }

                @Component
                @StepScope
                class AggregationReader {
                    private LocalDate from;
                    public void beforeStep(StepExecution execution) {
                        from = LocalDate.now();
                    }
                }
                """;

        // Сеттер, вызванный во время работы, по-прежнему делает поле общим изменяемым состоянием
        assertThat(RuleTests.lines(new MutableStateInSingletonBeanRule(), code)).containsExactly(7);
    }

    @Test
    void databaseDumpsAreNotMigrations() {
        List<String> lines = new ProjectFixture(dir)
                .write("fineract-db/multi-tenant-demo-backups/bk_demo.sql", "DROP TABLE m_staff;\n")
                .write("fineract-db/old-schema-files/core-ddl.sql", "DROP TABLE m_client;\n")
                .write("src/main/resources/sql/migrations/sample_data/load_sample_data.sql", "DROP TABLE m_loan;\n")
                .write("src/main/resources/db/migration/V2__drop.sql", "DROP TABLE m_legacy;\n")
                .lines(new SqlDestructiveStatementRule());

        assertThat(lines).containsExactly("V2__drop.sql:1");
    }

    @Test
    void notNullColumnsOfNewTableForEachDatabase(@TempDir Path other) {
        List<String> featureFile = new ProjectFixture(dir)
                .write("src/main/resources/db/changelog/parts/1002_allocation_rule.xml", """
                        <databaseChangeLog>
                          <changeSet author="fineract" id="1002-1">
                            <createTable tableName="m_allocation_rule">
                              <column name="id" type="bigint"/>
                            </createTable>
                          </changeSet>
                          <changeSet author="fineract" id="1002-2" context="mysql">
                            <addColumn tableName="m_allocation_rule">
                              <column name="created_on_utc" type="DATETIME(6)">
                                <constraints nullable="false"/>
                              </column>
                            </addColumn>
                          </changeSet>
                          <changeSet author="fineract" id="1002-3">
                            <addColumn tableName="m_loan">
                              <column name="rule_id" type="bigint">
                                <constraints nullable="false"/>
                              </column>
                            </addColumn>
                          </changeSet>
                        </databaseChangeLog>
                        """)
                .lines(new SqlNotNullWithoutDefaultRule());
        // Колонка для своей базы у только что созданной таблицы - часть той же доработки; m_loan создана раньше
        assertThat(featureFile).containsExactly("1002_allocation_rule.xml:16");

        // Общий журнал проекта: таблица создана давно, и в ней уже есть строки
        List<String> accumulated = new ProjectFixture(other)
                .write("src/main/resources/db/changelog/database-changelog.sql", """
                        --liquibase formatted sql
                        --changeset akvine:BOT-1
                        CREATE TABLE client (id BIGINT PRIMARY KEY);
                        --changeset akvine:BOT-22
                        ALTER TABLE client ADD bot_type VARCHAR(32) NOT NULL;
                        """)
                .lines(new SqlNotNullWithoutDefaultRule());
        assertThat(accumulated).containsExactly("database-changelog.sql:5");
    }

    @Test
    void handlerThatAlwaysThrowsRethrows() {
        List<String> lines = new ProjectFixture(dir)
                .source("GlAccountService.java", """
                        class GlAccountService {
                            @Transactional
                            public Result createAccount(Command command) {
                                try {
                                    repository.saveAndFlush(new GlAccount(command));
                                    return Result.ok();
                                } catch (DataIntegrityViolationException dve) {
                                    handleIntegrityIssues(command, dve);
                                    return Result.empty();
                                }
                            }
                            @Transactional
                            public Result updateAccount(Command command) {
                                try {
                                    repository.saveAndFlush(new GlAccount(command));
                                    return Result.ok();
                                } catch (DataIntegrityViolationException dve) {
                                    logIntegrityIssues(dve);
                                    return Result.empty();
                                }
                            }
                            private void handleIntegrityIssues(Command command, Exception dve) {
                                if (dve.getMessage().contains("gl_code")) {
                                    throw new DuplicateGlCodeException(command);
                                }
                                throw new PlatformDataIntegrityException("error.msg.unknown", dve);
                            }
                            private void logIntegrityIssues(Exception dve) {
                                log.error("Error occurred.", dve);
                            }
                        }
                        """)
                .lines(new CaughtExceptionInTransactionRule());

        // Обработчик бросает исключение на каждом пути - транзакция откатится как положено
        assertThat(lines).containsExactly("GlAccountService.java:17");
    }

    @Test
    void loopOverCollectionMeansItIsNotEmpty() {
        String code = """
                class MappingService {
                    List<Mapper> fetch(List<Mapping> mappings) {
                        List<Mapper> mappers = mappings.isEmpty() ? null : new ArrayList<>();
                        for (Mapping mapping : mappings) {
                            mappers.add(new Mapper(mapping));
                        }
                        return mappers;
                    }
                    int count(List<Mapping> mappings) {
                        List<Mapper> mappers = mappings.isEmpty() ? null : new ArrayList<>();
                        for (Mapping mapping : mappings) {
                            mappers.add(new Mapper(mapping));
                        }
                        return mappers.size();
                    }
                }
                """;

        // Внутри цикла список не пуст, а после цикла он мог оказаться пустым - и mappers остаться null
        assertThat(RuleTests.lines(new PossibleNullDereferenceRule(), code)).containsExactly(14);
    }

    @Test
    void nullOnlyForEmptyArgumentIsPassedThrough() {
        String code = """
                class JacksonUtil {
                    static JsonNode toJsonNode(String value) {
                        if (value == null || value.isEmpty()) {
                            return null;
                        }
                        return MAPPER.readTree(value);
                    }
                    static <T> T fromString(String string, Class<T> type) {
                        return string != null ? MAPPER.readValue(string, type) : null;
                    }
                    static JsonNode lookup(String key) {
                        if (CACHE.isEmpty()) {
                            return null;
                        }
                        return CACHE.get(key);
                    }
                    void use(String body) {
                        toJsonNode(body).get("method");
                        fromString(body, Rpc.class).run();
                        lookup(body).get("method");
                    }
                }
                """;

        // null на пустом аргументе - отражение аргумента; null на пустом кеше - собственный ответ метода
        assertThat(RuleTests.lines(new PossibleNullDereferenceRule(), code)).containsExactly(20);
    }

    @Test
    void propertiesFromOwnConfigNameAndPropertySource() {
        List<String> lines = new ProjectFixture(dir)
                .source("ThingsboardServerApplication.java", """
                        @SpringBootApplication
                        public class ThingsboardServerApplication {
                            private static final String SPRING_CONFIG_NAME_KEY = "--spring.config.name";
                            private static final String DEFAULT_SPRING_CONFIG_PARAM = SPRING_CONFIG_NAME_KEY + "=" + "thingsboard";
                            public static void main(String[] args) {
                                SpringApplication.run(ThingsboardServerApplication.class, DEFAULT_SPRING_CONFIG_PARAM);
                            }
                        }
                        """)
                .source("SignUpConfig.java", """
                        @Configuration
                        @PropertySource(value = "classpath:recaptcha.properties")
                        public class SignUpConfig {
                            @Value("${recaptcha.verification_url}")
                            private String verificationUrl;
                            @Value("${swagger.title}")
                            private String title;
                            @Value("${swagger.missing}")
                            private String missing;
                        }
                        """)
                .write("application/src/main/resources/thingsboard.yml", "swagger:\n  title: ThingsBoard\n")
                .write("application/src/main/resources/recaptcha.properties", "recaptcha.verification_url=https://x\n")
                .lines(new MissingConfigPropertyRule());

        assertThat(lines).containsExactly("SignUpConfig.java:8");
    }

    @Test
    void beanOutsideScannedPackagesAndSystemPropertiesNeedNoSettings() {
        List<String> lines = new ProjectFixture(dir)
                .source("ThingsboardServerApplication.java", """
                        package org.thingsboard.server;
                        @SpringBootApplication
                        @ComponentScan({"org.thingsboard.server", "org.thingsboard.script"})
                        public class ThingsboardServerApplication {
                        }
                        """)
                .source("EdgeGrpcClient.java", """
                        package org.thingsboard.edge.rpc;
                        @Service
                        public class EdgeGrpcClient {
                            @Value("${cloud.rpc.host}")
                            private String rpcHost;
                        }
                        """)
                .source("GitRepositoryService.java", """
                        package org.thingsboard.server.service.sync;
                        @Service
                        public class GitRepositoryService {
                            @Value("${java.io.tmpdir}")
                            private String tmpDir;
                            @Value("${vc.repositories-folder}")
                            private String repositoriesFolder;
                        }
                        """)
                .write("src/main/resources/application.yml", "server:\n  port: 8080\n")
                .lines(new MissingConfigPropertyRule());

        // Клиент для другого продукта в этом приложении не создается; системное свойство JVM задано всегда
        assertThat(lines).containsExactly("GitRepositoryService.java:6");
    }

    @Test
    void parsingFailureAndTimeoutKeepNothingWorthCause() {
        String code = """
                class Controllers {
                    RoleType roleType(String value) throws ThingsboardException {
                        try {
                            return RoleType.valueOf(value);
                        } catch (IllegalArgumentException e) {
                            throw new ThingsboardException("Unsupported role type " + value, BAD_REQUEST_PARAMS);
                        }
                    }
                    Result validate(Future<Result> future) {
                        try {
                            return future.get(10, TimeUnit.SECONDS);
                        } catch (TimeoutException e) {
                            throw new ThingsboardRuntimeException("Timeout to validate the configuration!");
                        }
                    }
                    void process(Entity entity) {
                        try {
                            convert(entity);
                            save(entity);
                        } catch (IllegalArgumentException e) {
                            throw new RuntimeException("Failed to process entity");
                        }
                    }
                }
                """;

        // Разбор строки и таймаут ничего не прячут; сбой обработки из нескольких шагов - теряет причину
        assertThat(RuleTests.lines(new LostExceptionCauseRule(), code)).containsExactly(21);
    }

    @Test
    void secretFlagIsNotSecret() {
        String code = """
                @Data
                class ComponentDescriptor {
                    private boolean hasSecrets;
                    private Boolean saveCredentials;
                    private String secret;
                }
                """;

        assertThat(RuleTests.lines(new SecretInToStringRule(), code)).containsExactly(5);
    }

    @Test
    void fallThroughNeedsBranchThatCanComplete() {
        String code = """
                class Edges {
                    Future<Void> process(ActionType type, EdgeId edgeId) {
                        switch (type) {
                            case UPDATED:
                                if (edgeId != null) {
                                    return save(edgeId);
                                } else {
                                    return pushToAll();
                                }
                            case DELETED:
                                try {
                                    return delete(edgeId);
                                } catch (RuntimeException e) {
                                    throw new IllegalStateException(e);
                                }
                            case ADDED:
                                notify(edgeId);
                            case ASSIGNED:
                                return assign(edgeId);
                            default:
                                return null;
                        }
                    }
                }
                """;

        assertThat(RuleTests.lines(new SwitchFallThroughRule(), code)).containsExactly(16);
    }

    @Test
    void allowedCircularReferencesLowerCycleToMinor() {
        ProjectFixture project = new ProjectFixture(dir)
                .source("AssetService.java", "@Service class AssetService { @Autowired private ProfileService profiles; }")
                .source("ProfileService.java", "@Service class ProfileService { @Autowired private AssetService assets; }");

        // Без настройки приложение с Boot 2.6 не стартует
        assertThat(project.check(new CircularDependencyRule())).singleElement()
                .satisfies(violation -> assertThat(violation.errorLevel()).isEqualTo(ErrorLevel.CRITICAL));

        project.write("src/main/resources/application.yml", "spring.main.allow-circular-references: \"true\"\n");
        assertThat(project.check(new CircularDependencyRule())).singleElement().satisfies(violation -> {
            assertThat(violation.errorLevel()).isEqualTo(ErrorLevel.MINOR);
            assertThat(violation.message()).contains("allow-circular-references");
        });
    }

    @Test
    void credentialsTypeConstantIsNotSecret() {
        String code = """
                class CredentialsService {
                    void check(Device device, DeviceCredentials credentials, String token) {
                        log.warn("[{}] Expected credentials type is {}", device.getId(), DeviceCredentialsType.ACCESS_TOKEN);
                        log.warn("[{}] Invalid token {}", device.getId(), token);
                    }
                }
                """;

        assertThat(RuleTests.lines(new SensitiveDataLoggingRule(), code)).containsExactly(4);
    }

    @Test
    void concurrentMapAndDeferredCallbackTolerateRemoval() {
        String code = """
                class Caches {
                    private final ConcurrentMap<String, Entry> versions = new ConcurrentHashMap<>();
                    private final Map<String, Stats> ipMap = new ConcurrentHashMap<>();
                    private final Map<String, Entry> plain = new HashMap<>();
                    void cleanup(long now, Set<String> toRead) {
                        for (Map.Entry<String, Entry> entry : versions.entrySet()) {
                            versions.remove(entry.getKey(), entry.getValue());
                        }
                        for (var entry : ipMap.entrySet()) {
                            ipMap.remove(entry.getKey());
                        }
                        toRead.forEach(id -> client.sendReadRequest(id, createDownlinkProxyCallback(() -> {
                            toRead.remove(id);
                        })));
                        for (Map.Entry<String, Entry> entry : plain.entrySet()) {
                            plain.remove(entry.getKey());
                        }
                    }
                }
                """;

        assertThat(RuleTests.lines(new CollectionModificationInForEachRule(), code)).containsExactly(16);
    }

    @Test
    void queryWithReturningRunsAsSelect() {
        String code = """
                interface AiModelRepository extends JpaRepository<AiModelEntity, UUID> {
                    @Modifying
                    @Query(value = "DELETE FROM ai_model WHERE tenant_id = :tenantId RETURNING id", nativeQuery = true)
                    Set<UUID> deleteByTenantId(@Param("tenantId") UUID tenantId);
                    @Query(value = "DELETE FROM device_credentials WHERE device_id = :deviceId RETURNING *", nativeQuery = true)
                    DeviceCredentialsEntity deleteByDeviceId(@Param("deviceId") UUID deviceId);
                    @Modifying
                    List<AiModelEntity> deleteByName(String name);
                    @Modifying
                    @Query("DELETE FROM AiModelEntity m WHERE m.name = :name")
                    List<AiModelEntity> deleteAllByName(@Param("name") String name);
                }
                """;

        // RETURNING возвращает строки, удаление по имени метода Spring строит сам; @Query без RETURNING
        // со списком в ответе упадет
        assertThat(RuleTests.lines(new ModifyingQueryMisuseRule(), code)).containsExactly(9);
    }

    @Test
    void failurePassedToCallbackAndReleaseAreNotSwallowed() {
        String code = """
                class RuleChainProcessor {
                    void onMsg(Msg msg) {
                        try {
                            process(msg);
                        } catch (Exception e) {
                            msg.getCallback().onFailure(new RuleEngineException(e.getMessage(), e));
                        }
                    }
                    void closeState(State state) {
                        try {
                            state.close();
                        } catch (Exception e) {
                            log.warn("Failed to close state", e);
                        }
                    }
                    void load(String id) {
                        try {
                            cache.get(id);
                        } catch (Exception e) {
                            log.warn("Failed to decode sessions", e);
                        }
                    }
                }
                """;

        assertThat(RuleTests.lines(new BroadCatchRule(), code)).containsExactly(19);
    }

    @Test
    void taskThatCatchesEverythingNeedsNoFuture() {
        String code = """
                class Listener {
                    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
                    void handle(Event event) {
                        executorService.submit(() -> {
                            log.trace("Event called: {}", event);
                            try {
                                service.publish(event);
                            } catch (Exception e) {
                                log.error("Failed to process event {}", event, e);
                            }
                        });
                        executorService.submit(() -> service.publish(event));
                    }
                }
                """;

        assertThat(RuleTests.lines(new IgnoredSubmitResultRule(), code)).containsExactly(12);
    }

    @Test
    void snapshotOfOwnModuleIsNotUnstable() {
        List<String> lines = new ProjectFixture(dir)
                .write("pom.xml", """
                        <project>
                          <groupId>org.thingsboard</groupId>
                          <artifactId>thingsboard</artifactId>
                          <version>4.4.1-SNAPSHOT</version>
                        </project>
                        """)
                .write("ui-ngx/pom.xml", """
                        <project>
                          <parent><groupId>org.thingsboard</groupId><artifactId>thingsboard</artifactId><version>4.4.1-SNAPSHOT</version></parent>
                          <artifactId>ui-ngx</artifactId>
                        </project>
                        """)
                .write("application/pom.xml", """
                        <project>
                          <parent><groupId>org.thingsboard</groupId><artifactId>thingsboard</artifactId><version>4.4.1-SNAPSHOT</version></parent>
                          <artifactId>application</artifactId>
                          <dependencies>
                            <dependency>
                              <groupId>org.thingsboard</groupId>
                              <artifactId>ui-ngx</artifactId>
                              <version>${project.version}</version>
                            </dependency>
                            <dependency>
                              <groupId>com.example</groupId>
                              <artifactId>client</artifactId>
                              <version>1.0-SNAPSHOT</version>
                            </dependency>
                          </dependencies>
                        </project>
                        """)
                .lines(new UnstableDependencyVersionRule());

        assertThat(lines).containsExactly("pom.xml:10");
    }

    @Test
    void exceptionOfLibraryTypeIsLoggedWithStack() {
        String code = """
                class Handlers {
                    void handle(Command command, Throwable realCause, NonTransientDataAccessException dve) {
                        log.error("Error occurred.", dve);
                        log.warn("Exception occurred", ErrorHandler.findMostSpecificException(dve));
                        log.error("Failed to import attributes for {} {}", type, id, thr);
                        log.error("Exception while closing of channel, integration [{}]", dve, name);
                    }
                }
                """;

        // Исключение не последним аргументом в подстановку попадет, а имя интеграции - нет
        assertThat(RuleTests.lines(new LogPlaceholderMismatchRule(), code)).containsExactly(6);
    }

    @Test
    void openApiSchemaClassIsNotMoney() {
        String code = """
                final class LoansApiResourceSwagger {
                    @Schema(description = "GetLoansResponse")
                    public static final class GetLoansResponse {
                        public Double amount;
                    }
                }
                class LoanSummary {
                    private Double amount;
                }
                """;

        assertThat(RuleTests.lines(new MoneyInFloatingPointRule(), code)).containsExactly(8);
    }

    @Test
    void separatorAfterContentIsIntendedPositiveIndex() {
        String code = """
                class Urls {
                    void parse(String relativeUrl, String email, String text) {
                        if (relativeUrl.indexOf('?') > 0) {}
                        if (email.indexOf("@") > 0) {}
                        if (text.indexOf("error") > 0) {}
                    }
                }
                """;

        // Разделитель в начале строки значит "перед ним пусто"; слово в начале строки - обычное совпадение
        assertThat(RuleTests.lines(new IndexOfPositiveRule(), code)).containsExactly(5);
    }

    @Test
    void ownStartupAnnotationMarksStartupMethod() throws IOException {
        Path sources = Files.createDirectories(dir.resolve("demo"));
        Files.writeString(sources.resolve("AfterStartUp.java"), """
                package demo;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.METHOD)
                @EventListener(ApplicationReadyEvent.class)
                public @interface AfterStartUp {
                }
                """);
        Files.writeString(sources.resolve("UpdateService.java"), """
                package demo;
                @Service
                public class UpdateService {
                    private String version;
                    private String latest;
                    @AfterStartUp
                    public void init() {
                        version = readVersion();
                    }
                    public void refresh() {
                        latest = fetchLatest();
                    }
                }
                """);
        List<SourceFile> loaded = new FileSystemSourceLoader().load(dir).sources();

        // Метод с аннотацией проекта, которая слушает запуск, выполняется один раз до обслуживания запросов
        assertThat(new MutableStateInSingletonBeanRule().checkProject(loaded)).extracting(Violation::line)
                .containsExactly(5);
    }

    @Test
    void assertionInsideHelperOfTestOrItsParent() {
        Map<String, String> files = Map.of(
                "AbstractMqttIntegrationTest.java", """
                        abstract class AbstractMqttIntegrationTest {
                            protected void processTest(Config config, int times) {
                                integration.start(config);
                                checkUnsubscribed(times);
                            }
                            private void checkUnsubscribed(int times) {
                                verify(mqttClient, times(times)).off(any());
                            }
                            protected void start(Config config) {
                                integration.start(config);
                            }
                        }
                        """,
                "MqttIntegrationTest.java", """
                        class MqttIntegrationTest extends AbstractMqttIntegrationTest {
                            @Test
                            void unsubscribe() {
                                processTest(config, 1);
                            }
                            @Test
                            void startsOnly() {
                                start(config);
                            }
                            @Test
                            void getCustomMenuDoesNotThrow() {
                                client.getCustomMenu();
                            }
                        }
                        """);

        // Проверка двумя вызовами глубже в родителе считается; метод без проверок - нет
        assertThat(RuleTests.checkProject(new TestWithoutAssertionRule(), files))
                .extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("MqttIntegrationTest.java:6");
    }

    @Test
    void libraryTypeNamedRepositoryIsNotDatabase() {
        String code = """
                package ru.akvine.zond.rules.logical;

                import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

                class QueryRule {
                    void check(List<ClassOrInterfaceDeclaration> types) {
                        for (ClassOrInterfaceDeclaration repository : types) {
                            repository.getMethods();
                        }
                    }
                    void load(List<Long> ids) {
                        for (Long id : ids) {
                            userRepository.findById(id);
                        }
                    }
                }
                """;

        // Узел дерева разбора с именем repository к базе не ходит; поле userRepository без типа - по имени
        assertThat(RuleTests.lines(new RepositoryCallInLoopRule(), code)).containsExactly(13);
    }

    @Test
    void filledConstantListIsNotEmpty() {
        String code = """
                class Scopes {
                    private static final List<String> SHARED_SCOPES = List.of("singleton", "application");
                    private static final List<String> EMPTY = List.of();
                    private final List<String> names = List.of("a");
                    String defaults(List<String> loaded) {
                        String first = SHARED_SCOPES.get(0);
                        String none = EMPTY.get(0);
                        String own = names.get(0);
                        return loaded.get(0);
                    }
                }
                """;

        // Константа с элементами пустой не бывает; поле экземпляра могут переприсвоить, параметр - любой
        assertThat(RuleTests.lines(new ListGetFirstWithoutCheckRule(), code)).containsExactly(7, 8, 9);
    }

    @Test
    void booleanAssignedFromPrimitiveIsNotNull() {
        String code = """
                class AlarmRuleState {
                    private Boolean active;
                    private Boolean cleared;
                    AlarmEvalResult eval(long ts, boolean late) {
                        active = isActive(ts);
                        if (!active) {
                            return AlarmEvalResult.FALSE;
                        }
                        if (late) {
                            cleared = ts > 0;
                        }
                        if (cleared) {
                            return AlarmEvalResult.CLEARED;
                        }
                        return AlarmEvalResult.TRUE;
                    }
                    private boolean isActive(long ts) {
                        return ts > 0;
                    }
                }
                """;

        // Присваивание только в одной ветке не гарантирует значение
        assertThat(RuleTests.lines(new NullUnboxingRule(), code)).containsExactly(12);
    }
}
