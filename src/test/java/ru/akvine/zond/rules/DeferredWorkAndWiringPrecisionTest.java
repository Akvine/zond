package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.BusinessErrorAsBadRequestRule;
import ru.akvine.zond.rules.concurrency.SharedBeanReconfigurationRule;
import ru.akvine.zond.rules.logical.PossibleNullDereferenceRule;
import ru.akvine.zond.rules.performance.RepositoryCallInLoopRule;
import ru.akvine.zond.rules.resources.HttpClientWithoutTimeoutRule;
import ru.akvine.zond.rules.security.SecretInConfigRule;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уточнения прежних правил: код, который выполняется не там, где записан (задача в расписании, задача в пуле
 * потоков), и бины, которые различаются именем, а не типом
 */
class DeferredWorkAndWiringPrecisionTest {
    private static final String REPORT_JOB = """
            @Component
            public class ReportJob {
                private final ReportRepository reportRepository;
                public void build(String tenant) {
                    reportRepository.findByTenant(tenant);
                }
            }
            """;
    private static final String REPORT_REPOSITORY = """
            public interface ReportRepository extends JpaRepository<Report, Long> {
                List<Report> findByTenant(String tenant);
            }
            """;

    private static Map<String, String> reports(String schedules) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("ReportRepository.java", REPORT_REPOSITORY);
        files.put("ReportJob.java", REPORT_JOB);
        files.put("Schedules.java", schedules);
        return files;
    }

    @Test
    void taskThatLoopOnlyRegistersInSchedulerIsNotQueryPerElement() {
        // Лямбда в цикле только ставится в расписание: запрос пойдет позже и по расписанию, а не по числу банков
        assertThat(RuleTests.checkProject(new RepositoryCallInLoopRule(), reports("""
                @Configuration
                public class Schedules implements SchedulingConfigurer {
                    private final ReportJob reportJob;
                    private final List<String> tenants;
                    @Override
                    public void configureTasks(ScheduledTaskRegistrar registrar) {
                        tenants.forEach(tenant -> {
                            register(registrar, tenant);
                            registrar.addCronTask(() -> reportJob.build(tenant), "0 0 * * * *");
                        });
                    }
                    private void register(ScheduledTaskRegistrar registrar, String tenant) {
                        Runnable task = () -> reportJob.build(tenant);
                        registrar.addFixedDelayTask(task, 5000);
                    }
                }
                """))).isEmpty();
    }

    @Test
    void taskRunInsideLoopIsStillQueryPerElement() {
        // Та же лямбда, но ее выполняют тут же, на каждом элементе
        List<Violation> violations = RuleTests.checkProject(new RepositoryCallInLoopRule(), reports("""
                @Component
                public class Schedules {
                    private final ReportJob reportJob;
                    public void rebuild(List<String> tenants) {
                        for (String tenant : tenants) {
                            Runnable task = () -> reportJob.build(tenant);
                            task.run();
                            reportJob.build(tenant);
                        }
                    }
                }
                """));
        assertThat(violations).extracting(Violation::line).containsExactly(6, 8);
    }

    @Test
    void queryInsideTaskHandedToPoolIsOnlySuspicion() {
        List<Violation> violations = RuleTests.checkProject(new RepositoryCallInLoopRule(), reports("""
                @Component
                public class Schedules {
                    private final ReportJob reportJob;
                    private final ExecutorService executor;
                    public void rebuild(List<String> tenants) {
                        for (String tenant : tenants) {
                            executor.submit(() -> reportJob.build(tenant));
                        }
                        tenants.forEach(tenant -> {
                            Runnable task = () -> reportJob.build(tenant);
                            executor.execute(task);
                        });
                        for (String tenant : tenants) {
                            reportJob.build(tenant);
                        }
                    }
                }
                """));
        assertThat(violations).extracting(Violation::line).containsExactly(7, 10, 14);
        assertThat(violations).extracting(Violation::confidenceOrDefault)
                .containsExactly(Confidence.SUSPICION, Confidence.SUSPICION, Confidence.PROBABLE);
    }

    @Test
    void assertionCalledWithFullClassNameProtectsValue() {
        assertThat(RuleTests.lines(new PossibleNullDereferenceRule(), """
                class Loader {
                    Item find(long id) {
                        if (id < 0) {
                            return null;
                        }
                        return new Item();
                    }
                    String qualified(long id) {
                        Item item = find(id);
                        org.springframework.util.Assert.notNull(item, "item is null");
                        return item.getName();
                    }
                    String unchecked(long id) {
                        Item item = find(id);
                        return item.getName();
                    }
                    String notClass(long id) {
                        Item item = find(id);
                        registry.checks.notNull(item);
                        return item.getName();
                    }
                }
                """)).containsExactly(15, 20);
    }

    @Test
    void ownExceptionNamedAfterAnotherStatusIsNotBusinessRejection() {
        Map<String, String> files = new LinkedHashMap<>();
        // Свои исключения проекта, но по имени это ошибка данных запроса, доступ, сессия, конфликт
        for (String name : List.of("MethodArgumentNotValidException", "BadCredentialsException",
                "WrongSessionException", "WebAppBadAuthDataException", "MethodNotSupportedException",
                "LinkTooLongException", "EmailExistsException", "DeskAlreadyFoundException", "CardLimitException")) {
            files.put(name + ".java", "public class " + name + " extends RuntimeException {\n}\n");
        }
        files.put("Errors.java", """
                @RestControllerAdvice
                public class Errors {
                    @ExceptionHandler({MethodArgumentNotValidException.class})
                    public ResponseEntity<String> invalid(MethodArgumentNotValidException e) {
                        return new ResponseEntity<>("field", HttpStatus.BAD_REQUEST);
                    }
                    @ExceptionHandler({BadCredentialsException.class, WrongSessionException.class,
                            WebAppBadAuthDataException.class})
                    public ResponseEntity<String> access(RuntimeException e) {
                        return new ResponseEntity<>("access", HttpStatus.BAD_REQUEST);
                    }
                    @ExceptionHandler({MethodNotSupportedException.class, LinkTooLongException.class})
                    public ResponseEntity<String> request(RuntimeException e) {
                        return new ResponseEntity<>("request", HttpStatus.BAD_REQUEST);
                    }
                    @ExceptionHandler({EmailExistsException.class, DeskAlreadyFoundException.class})
                    public ResponseEntity<String> conflict(RuntimeException e) {
                        return new ResponseEntity<>("conflict", HttpStatus.BAD_REQUEST);
                    }
                    @ExceptionHandler(CardLimitException.class)
                    public ResponseEntity<String> limit(CardLimitException e) {
                        return new ResponseEntity<>("limit", HttpStatus.BAD_REQUEST);
                    }
                }
                """);
        List<Violation> violations = RuleTests.checkProject(new BusinessErrorAsBadRequestRule(), files);
        assertThat(violations).extracting(Violation::line).containsExactly(22);
        assertThat(violations.get(0).message()).contains("CardLimitException");
    }

    @Test
    void secretInSettingsFileClosedByGitignoreIsOnlySuspicion(@TempDir Path project) {
        SecretInConfigRule rule = new SecretInConfigRule();
        List<Violation> violations = new ProjectFixture(project)
                .write(".gitignore", "target/\n/app/src/main/resources/application-*.properties\n")
                .write("app/src/main/resources/application.properties", "billing.api.secret=Zx81kQp0\n")
                .write("app/src/main/resources/application-BANK.properties", "billing.api.secret=Zx81kQp0\n")
                .context().configFiles().stream()
                .flatMap(file -> rule.checkConfig(file).stream())
                .sorted((first, second) -> first.file().compareTo(second.file()))
                .toList();
        assertThat(violations).extracting(violation -> violation.file().getFileName().toString())
                .containsExactly("application-BANK.properties", "application.properties");
        // Файл профиля в хранилище не попадает: так секреты и положено держать
        assertThat(violations.get(0).confidenceOrDefault()).isEqualTo(Confidence.SUSPICION);
        assertThat(violations.get(0).errorLevel()).isEqualTo(ErrorLevel.MINOR);
        assertThat(violations.get(0).message()).contains(".gitignore", "git ls-files");
        assertThat(violations.get(1).confidenceOrDefault()).isEqualTo(Confidence.PROBABLE);
        assertThat(violations.get(1).errorLevel()).isEqualTo(ErrorLevel.CRITICAL);
    }

    @Test
    void bareClientInItsOwnFieldIsFoundNextToTunedOne() {
        // Сроки заданы другому клиенту; поле notifier остается как создано и только вызывается
        assertThat(RuleTests.lines(new HttpClientWithoutTimeoutRule(), """
                @Configuration
                public class BotConfig {
                    private final RestTemplate notifier = new RestTemplate();
                    @Bean
                    public RestTemplate senderRestTemplate(ClientBuilder builder) {
                        RestTemplate notifier = builder.withConnectTimeout(2000).withReadTimeout(5000).build();
                        return notifier;
                    }
                    private Info webhookInfo(String url) {
                        return this.notifier.getForObject(url, Info.class);
                    }
                    private void register(String url) {
                        notifier.postForObject(url, null, Info.class);
                    }
                }
                """)).containsExactly(3);
    }

    @Test
    void clientThatMayGetTimeoutsElsewhereIsNotJudged() {
        // Клиенту дают фабрику запросов, его передают дальше или возвращают: сроки могли прийти оттуда
        assertThat(RuleTests.lines(new HttpClientWithoutTimeoutRule(), """
                @Configuration
                public class BotConfig {
                    private final RestTemplate tuned = new RestTemplate();
                    private final RestTemplate passed = new RestTemplate();
                    @PostConstruct
                    void init() {
                        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                        factory.setConnectTimeout(2000);
                        tuned.setRequestFactory(factory);
                        customize(passed);
                    }
                    @Bean
                    public RestTemplate returned() {
                        RestTemplate template = new RestTemplate();
                        return template;
                    }
                    public OkHttpClient okHttp() {
                        return new OkHttpClient();
                    }
                }
                """)).isEmpty();
        // У OkHttp сроки по умолчанию есть, и без них в файле о клиенте сказать нечего
        assertThat(RuleTests.lines(new HttpClientWithoutTimeoutRule(), """
                class Calls {
                    private final OkHttpClient http = new OkHttpClient();
                    private final RestTemplate rest = new RestTemplate();
                }
                """)).containsExactly(3);
    }

    private static final String TWO_CLIENTS = """
            @Configuration
            public class Clients {
                @Bean
                public RestTemplate billingRestTemplate() {
                    return new RestTemplate();
                }
                @Bean("catalogRestTemplate")
                public RestTemplate catalog() {
                    return new RestTemplate();
                }
            }
            """;
    private static final String BILLING_CLIENT = """
            @Service
            @RequiredArgsConstructor
            public class BillingClient {
                private final RestTemplate billingRestTemplate;
                @PostConstruct
                public void init() {
                    billingRestTemplate.setErrorHandler(new QuietErrorHandler());
                }
            }
            """;

    private static Map<String, String> clients(String configuration, String other) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Clients.java", configuration);
        files.put("BillingClient.java", BILLING_CLIENT);
        files.put("Other.java", other);
        return files;
    }

    @Test
    void beanOfSameTypeButAnotherNameIsNotShared() {
        // Бинов RestTemplate два, и каждый класс получает свой - по имени поля
        assertThat(RuleTests.checkProject(new SharedBeanReconfigurationRule(), clients(TWO_CLIENTS, """
                @Service
                @RequiredArgsConstructor
                public class CatalogClient {
                    private final RestTemplate catalogRestTemplate;
                }
                """))).isEmpty();
        assertThat(RuleTests.checkProject(new SharedBeanReconfigurationRule(), clients(TWO_CLIENTS, """
                @Service
                public class CatalogClient {
                    private final RestTemplate client;
                    public CatalogClient(@Qualifier("catalogRestTemplate") RestTemplate restTemplate) {
                        this.client = restTemplate;
                    }
                }
                """))).isEmpty();
    }

    @Test
    void beanInjectedUnderSameNameElsewhereIsShared() {
        List<Violation> sameName = RuleTests.checkProject(new SharedBeanReconfigurationRule(), clients(TWO_CLIENTS, """
                @Service
                public class Invoices {
                    private final RestTemplate http;
                    public Invoices(RestTemplate billingRestTemplate) {
                        this.http = billingRestTemplate;
                    }
                }
                """));
        assertThat(sameName).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("BillingClient.java:7");
        assertThat(sameName.get(0).message()).contains("внедрен еще в Invoices");
        // Бин этого типа один: кто бы как ни назвал поле, получает его
        assertThat(RuleTests.checkProject(new SharedBeanReconfigurationRule(), clients("""
                @Configuration
                public class Clients {
                    @Bean
                    public RestTemplate restTemplate() {
                        return new RestTemplate();
                    }
                }
                """, """
                @Service
                @RequiredArgsConstructor
                public class CatalogClient {
                    private final RestTemplate catalogRestTemplate;
                }
                """))).extracting(Violation::line).containsExactly(7);
    }
}
