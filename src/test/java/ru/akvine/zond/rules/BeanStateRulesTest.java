package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.concurrency.SharedBeanReconfigurationRule;
import ru.akvine.zond.rules.concurrency.StatefulBeanAsSingletonRule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:360 и jr:361: объект с изменяемым состоянием, общий для всех потоков через бин-одиночку
 */
class BeanStateRulesTest {

    @Test
    void statefulObjectMustNotBeSingletonBean() {
        List<Violation> violations = RuleTests.check(new StatefulBeanAsSingletonRule(), """
                import java.sql.Connection;
                @Configuration
                class Beans {
                    @Bean
                    SimpleDateFormat dateFormat() { return new SimpleDateFormat("dd.MM.yyyy"); }
                    @Bean
                    @Scope("prototype")
                    SimpleDateFormat ownDateFormat() { return new SimpleDateFormat("dd.MM.yyyy"); }
                    @Bean
                    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
                    MessageDigest ownDigest() throws Exception { return MessageDigest.getInstance("SHA-256"); }
                    @Bean
                    MessageDigest digest() throws Exception { return MessageDigest.getInstance("SHA-256"); }
                    @Bean
                    WebClient.Builder webClientBuilder() { return WebClient.builder(); }
                    @Bean
                    @RequestScope
                    WebClient.Builder requestBuilder() { return WebClient.builder(); }
                    @Bean
                    Connection connection(DataSource dataSource) throws Exception { return dataSource.getConnection(); }
                    @Bean
                    EntityManager entityManager(EntityManagerFactory factory) { return factory.createEntityManager(); }
                    @Bean
                    RestTemplate restTemplate() { return new RestTemplate(); }
                    @Bean
                    ObjectMapper objectMapper() { return new ObjectMapper(); }
                    @Bean
                    DateTimeFormatter formatter() { return DateTimeFormatter.ISO_DATE; }
                    @Bean
                    ThreadLocal<SimpleDateFormat> perThread() { return ThreadLocal.withInitial(SimpleDateFormat::new); }
                    @Bean
                    @Scope("singleton")
                    Cipher cipher() throws Exception { return Cipher.getInstance("AES"); }
                    SimpleDateFormat helper() { return new SimpleDateFormat(); }
                }
                """);

        // RestTemplate и ObjectMapper потокобезопасны, пока их не перенастраивают: одиночкой им быть положено
        assertThat(violations).extracting(Violation::line).containsExactly(4, 12, 14, 19, 21, 31);
        assertThat(violations.get(0).message())
                .contains("@Scope(\"prototype\")")
                .contains("ObjectProvider")
                .contains("не через ApplicationContext.getBean()")
                .contains("DateTimeFormatter");
        assertThat(violations.get(2).message()).contains("WebClient.Builder");
        assertThat(violations.get(4).message()).contains("EntityManager");
    }

    @Test
    void jdbcNamesAreCheckedOnlyWithJdbcImport() {
        // Connection из другой библиотеки (JMS, AMQP) потокобезопасен
        assertThat(RuleTests.lines(new StatefulBeanAsSingletonRule(), """
                import jakarta.jms.Connection;
                @Configuration
                class Beans {
                    @Bean
                    Connection connection(ConnectionFactory factory) throws Exception { return factory.createConnection(); }
                }
                """)).isEmpty();
    }

    @Test
    void ownClassWithLibraryNameIsNotStateful() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Transformer.java", "class Transformer { String apply(String value) { return value; } }");
        files.put("Beans.java", """
                @Configuration
                class Beans {
                    @Bean
                    Transformer transformer() { return new Transformer(); }
                }
                """);

        assertThat(RuleTests.checkProject(new StatefulBeanAsSingletonRule(), files)).isEmpty();
    }

    @Test
    void statefulObjectInFieldOfSingletonIsShared() {
        List<Violation> violations = RuleTests.check(new StatefulBeanAsSingletonRule(), """
                @Service
                class ReportService {
                    private final SimpleDateFormat format = new SimpleDateFormat("dd.MM.yyyy");
                    private final MessageDigest digest;
                    private final StringBuilder buffer = new StringBuilder();
                    private final DecimalFormat guarded = new DecimalFormat("#.##");
                    private final NumberFormat unused = NumberFormat.getInstance();
                    private final Calendar injected;
                    private final DateTimeFormatter safe = DateTimeFormatter.ISO_DATE;
                    private static final SimpleDateFormat STATIC_FORMAT = new SimpleDateFormat("yyyy");
                    private static final Collator COLLATOR = Collator.getInstance();
                    ReportService(Calendar injected) throws Exception {
                        this.injected = injected;
                        this.digest = MessageDigest.getInstance("SHA-256");
                    }
                    String title(Date date) {
                        buffer.append(date);
                        return format.format(date) + STATIC_FORMAT.format(date) + injected.getTime();
                    }
                    byte[] hash(byte[] data) {
                        return this.digest.digest(data);
                    }
                    synchronized String amount(double value) {
                        return guarded.format(value);
                    }
                    int compare(String left, String right) {
                        return COLLATOR.compare(left, right);
                    }
                }
                """);

        // Поле под synchronized и поле без обращений не делятся; внедренный объект проверяется там, где объявлен
        // его бин; статические SimpleDateFormat и Calendar показывает отдельное правило
        assertThat(violations).extracting(Violation::line).containsExactly(3, 4, 5, 11);
        assertThat(violations.get(0).message()).contains("в бине-одиночке 'ReportService'").contains("в методе, где он нужен");
    }

    @Test
    void fieldsOfShortLivedBeansAndPlainClassesAreNotShared() {
        assertThat(RuleTests.lines(new StatefulBeanAsSingletonRule(), """
                @Component
                @Scope("prototype")
                class Exporter {
                    private final SimpleDateFormat format = new SimpleDateFormat("dd.MM.yyyy");
                    String title(Date date) { return format.format(date); }
                }
                class Plain {
                    private final SimpleDateFormat format = new SimpleDateFormat("dd.MM.yyyy");
                    String title(Date date) { return format.format(date); }
                }
                """)).isEmpty();
    }

    @Test
    void sharedBeanMustNotBeReconfiguredAtRuntime() {
        List<Violation> violations = RuleTests.check(new SharedBeanReconfigurationRule(), """
                @Service
                @RequiredArgsConstructor
                class PartnerClient {
                    private static final ObjectMapper STATIC_MAPPER = new ObjectMapper();
                    private final RestTemplate restTemplate;
                    private final ObjectMapper objectMapper;
                    private final JdbcTemplate jdbcTemplate;
                    private final TransactionTemplate transactionTemplate;
                    private final ModelMapper modelMapper;
                    private final RabbitTemplate rabbitTemplate;
                    String send(String token, Object body) throws Exception {
                        restTemplate.getInterceptors().add(new TokenInterceptor(token));
                        restTemplate.setErrorHandler(new SilentErrorHandler());
                        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                        this.objectMapper.registerModule(new JavaTimeModule());
                        String json = objectMapper.writeValueAsString(body);
                        ObjectMapper own = objectMapper.copy();
                        own.configure(SerializationFeature.INDENT_OUTPUT, true);
                        return restTemplate.postForObject("/send", json, String.class);
                    }
                    List<Row> load() {
                        jdbcTemplate.setFetchSize(1000);
                        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                        modelMapper.getConfiguration().setAmbiguityIgnored(true);
                        rabbitTemplate.setExchange("orders");
                        STATIC_MAPPER.enable(SerializationFeature.INDENT_OUTPUT);
                        return jdbcTemplate.query("select 1", mapper);
                    }
                    void local(ObjectMapper objectMapper) {
                        objectMapper.registerModule(new JavaTimeModule());
                    }
                }
                """);

        // Копия и параметр метода - не общий объект
        assertThat(violations).extracting(Violation::line).containsExactly(12, 13, 14, 15, 22, 23, 24, 25, 26);
        assertThat(violations.get(0).message())
                .contains("'restTemplate' (RestTemplate)")
                .contains("в список добавляется еще один элемент")
                .contains("RestTemplateBuilder")
                .contains("ObjectProvider")
                .contains("не через ApplicationContext.getBean()");
        assertThat(violations.get(2).message()).contains("copy()").doesNotContain("в список добавляется");
        assertThat(violations.get(5).message()).contains("new TransactionTemplate(transactionManager)");
    }

    @Test
    void configurationAtStartupIsAllowed() {
        assertThat(RuleTests.lines(new SharedBeanReconfigurationRule(), """
                @Service
                class Exporter implements CommandLineRunner {
                    private static final ObjectMapper MAPPER = new ObjectMapper();
                    static {
                        MAPPER.registerModule(new JavaTimeModule());
                    }
                    private final ObjectMapper objectMapper;
                    private final RestTemplate restTemplate;
                    private ObjectMapper lazy;
                    Exporter(ObjectMapper objectMapper, RestTemplate restTemplate) {
                        this.objectMapper = objectMapper;
                        this.restTemplate = restTemplate;
                        objectMapper.registerModule(new JavaTimeModule());
                        setUpClient();
                    }
                    @PostConstruct
                    void prepare() {
                        objectMapper.configure(SerializationFeature.INDENT_OUTPUT, true);
                        setUpMapper();
                    }
                    private void setUpClient() {
                        restTemplate.setErrorHandler(new SilentErrorHandler());
                    }
                    private void setUpMapper() {
                        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
                    }
                    @Override
                    public void run(String... args) {
                        restTemplate.getInterceptors().add(new LoggingInterceptor());
                    }
                    ObjectMapper lazy() {
                        if (lazy == null) {
                            lazy = new ObjectMapper();
                            lazy.registerModule(new JavaTimeModule());
                        }
                        return lazy;
                    }
                }
                """)).isEmpty();
    }

    private static final String PARTNER_CLIENT = """
            @Service
            class PartnerClient {
                private final RestTemplate restTemplate;
                private final ObjectMapper objectMapper;
                PartnerClient(RestTemplate restTemplate, ObjectMapper objectMapper) {
                    this.restTemplate = restTemplate;
                    this.objectMapper = objectMapper;
                    restTemplate.getInterceptors().add(new TokenInterceptor());
                }
                @PostConstruct
                void prepare() {
                    restTemplate.setErrorHandler(new SilentErrorHandler());
                    objectMapper.registerModule(new JavaTimeModule());
                }
            }
            """;

    @Test
    void consumerMustNotConfigureBeanSharedWithOthers() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("PartnerClient.java", PARTNER_CLIENT);
        files.put("BillingClient.java", "@Service @RequiredArgsConstructor class BillingClient { private final RestTemplate restTemplate; }");
        files.put("MailClient.java", "@Component class MailClient { @Autowired private RestTemplate restTemplate; }");

        List<Violation> violations = RuleTests.checkProject(new SharedBeanReconfigurationRule(), files);

        // ObjectMapper внедрен только в этот класс: его настройка никого больше не задевает
        assertThat(violations).extracting(Violation::line).containsExactly(12, 8);
        assertThat(violations).allSatisfy(violation -> assertThat(violation.message())
                .contains("BillingClient, MailClient")
                .contains("@Scope(\"prototype\")")
                .contains("@Qualifier")
                .contains("а не через ApplicationContext.getBean()"));
        assertThat(violations.get(0).message()).contains("в методе 'prepare'");
        assertThat(violations.get(1).message()).contains("в конструкторе");
    }

    @Test
    void prototypeBeanBelongsToItsConsumer() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("PartnerClient.java", PARTNER_CLIENT);
        files.put("BillingClient.java", "@Service @RequiredArgsConstructor class BillingClient { private final RestTemplate restTemplate; }");
        files.put("HttpConfig.java", """
                @Configuration
                class HttpConfig {
                    @Bean
                    @Scope("prototype")
                    RestTemplate restTemplate(RestTemplateBuilder builder) { return builder.build(); }
                }
                """);

        // У каждого потребителя свой экземпляр - настраивать его можно свободно
        assertThat(RuleTests.checkProject(new SharedBeanReconfigurationRule(), files)).isEmpty();
    }

    @Test
    void qualifiedAndOwnObjectsAreNotShared() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("BillingClient.java", "@Service @RequiredArgsConstructor class BillingClient { private final RestTemplate restTemplate; }");
        files.put("QualifiedClient.java", """
                @Service
                class QualifiedClient {
                    private final RestTemplate restTemplate;
                    QualifiedClient(@Qualifier("partner") RestTemplate restTemplate) {
                        this.restTemplate = restTemplate;
                        restTemplate.getInterceptors().add(new TokenInterceptor());
                    }
                }
                """);
        files.put("OwnClient.java", """
                @Service
                class OwnClient {
                    private final RestTemplate restTemplate = new RestTemplate();
                    @PostConstruct
                    void prepare() {
                        restTemplate.getInterceptors().add(new TokenInterceptor());
                    }
                }
                """);

        assertThat(RuleTests.checkProject(new SharedBeanReconfigurationRule(), files)).isEmpty();
    }

    @Test
    void configurationClassesAndShortLivedBeansAreSkipped() {
        assertThat(RuleTests.lines(new SharedBeanReconfigurationRule(), """
                @Configuration
                class WebConfig implements WebMvcConfigurer {
                    @Autowired
                    private ObjectMapper objectMapper;
                    @Override
                    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
                        objectMapper.registerModule(new JavaTimeModule());
                    }
                }
                @Component
                @Scope("prototype")
                class Importer {
                    private final ObjectMapper objectMapper = new ObjectMapper();
                    void run() { objectMapper.registerModule(new JavaTimeModule()); }
                }
                class Plain {
                    private final ObjectMapper objectMapper = new ObjectMapper();
                    void run() { objectMapper.registerModule(new JavaTimeModule()); }
                }
                """)).isEmpty();
    }
}
