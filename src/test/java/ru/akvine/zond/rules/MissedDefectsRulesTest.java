package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.BusinessErrorAsBadRequestRule;
import ru.akvine.zond.rules.codesmell.UnusedClassRule;
import ru.akvine.zond.rules.codesmell.ValueWithoutDefaultRule;
import ru.akvine.zond.rules.logical.ListGetFirstWithoutCheckRule;
import ru.akvine.zond.rules.logical.MissingEnableAnnotationRule;
import ru.akvine.zond.rules.performance.LazyRelationInMappingRule;
import ru.akvine.zond.rules.resources.HttpClientWithoutTimeoutRule;
import ru.akvine.zond.rules.security.ActuatorExposureRule;
import ru.akvine.zond.rules.security.SensitiveDataLoggingRule;
import ru.akvine.zond.rules.security.WeakCipherRule;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Дефекты, которые нашлись в рабочем приложении запуском и чтением кода, а правила их пропускали: в каждом тесте
 * код, на котором правило обязано сработать, и похожий код, где находки быть не должно
 */
class MissedDefectsRulesTest {

    @Test
    void apacheClientWithoutTimeoutsIsFound() {
        assertThat(RuleTests.lines(new HttpClientWithoutTimeoutRule(), """
                class MaxClient {
                    // срок опроса - параметр запроса, а не срок ожидания клиента
                    private String pollingTimeoutSeconds;
                    RestTemplate build(SSLConnectionSocketFactory socketFactory) {
                        PoolingHttpClientConnectionManager connectionManager =
                                PoolingHttpClientConnectionManagerBuilder.create()
                                        .setSSLSocketFactory(socketFactory)
                                        .build();
                        CloseableHttpClient httpClient =
                                HttpClients.custom().setConnectionManager(connectionManager).build();
                        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
                    }
                    CloseableHttpClient plain() {
                        return HttpClients.createDefault();
                    }
                    CloseableHttpClient built() {
                        return HttpClientBuilder.create().build();
                    }
                }
                """)).containsExactly(10, 14, 17);
    }

    @Test
    void apacheClientWithTimeoutsOrReadyPartsIsFine() {
        assertThat(RuleTests.lines(new HttpClientWithoutTimeoutRule(), """
                class Configured {
                    CloseableHttpClient client() {
                        RequestConfig config = RequestConfig.custom()
                                .setResponseTimeout(Timeout.ofSeconds(5))
                                .build();
                        return HttpClients.custom().setDefaultRequestConfig(config).build();
                    }
                }
                """)).isEmpty();
        assertThat(RuleTests.lines(new HttpClientWithoutTimeoutRule(), """
                class Assembled {
                    private final HttpClientConnectionManager connectionManager;
                    CloseableHttpClient fromField() {
                        return HttpClients.custom().setConnectionManager(connectionManager).build();
                    }
                    CloseableHttpClient fromParameter(HttpClientConnectionManager manager) {
                        return HttpClients.custom().setConnectionManager(manager).build();
                    }
                    CloseableHttpClient fromFactory(ConnectionFactory factory) {
                        return HttpClients.custom().setConnectionManager(factory.manager()).build();
                    }
                }
                """)).isEmpty();
    }

    @Test
    void httpConnectionWithoutTimeoutsIsFound() {
        assertThat(RuleTests.lines(new HttpClientWithoutTimeoutRule(), """
                class Downloader {
                    byte[] download(String address) throws IOException {
                        URL url = new URL(address);
                        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                        return connection.getInputStream().readAllBytes();
                    }
                    long modified(URL resource) throws IOException {
                        // ресурс из classpath: сети здесь нет
                        return resource.openConnection().getLastModified();
                    }
                }
                """)).containsExactly(4);
        assertThat(RuleTests.lines(new HttpClientWithoutTimeoutRule(), """
                class Downloader {
                    byte[] download(String address) throws IOException {
                        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
                        connection.setConnectTimeout(3_000);
                        connection.setReadTimeout(10_000);
                        return connection.getInputStream().readAllBytes();
                    }
                }
                """)).isEmpty();
    }

    @Test
    void timeoutGetterDoesNotCountAsTimeoutSetting() {
        assertThat(RuleTests.lines(new HttpClientWithoutTimeoutRule(), """
                class Poller {
                    private final RestTemplate restTemplate = new RestTemplate();
                    Update[] poll(Settings settings) {
                        return restTemplate.getForObject(url, Update[].class, settings.getPollingTimeout());
                    }
                }
                """)).containsExactly(2);
        // Срок у ограничителя времени Resilience4j к самому клиенту не относится
        assertThat(RuleTests.lines(new HttpClientWithoutTimeoutRule(), """
                class GatewayConfig {
                    @Bean
                    RestTemplate restTemplate() {
                        return new RestTemplate();
                    }
                    @Bean
                    Customizer<Resilience4JCircuitBreakerFactory> customizer() {
                        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                                .timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(SECONDS).build())
                                .build());
                    }
                }
                """)).containsExactly(4);
        // Сроки клиента задают в этом же файле
        assertThat(RuleTests.lines(new HttpClientWithoutTimeoutRule(), """
                class ClientConfig {
                    RestTemplate plain() {
                        return new RestTemplate();
                    }
                    OkHttpClient tuned() {
                        return new OkHttpClient.Builder().callTimeout(SECONDS).readTimeout(SECONDS).build();
                    }
                }
                """)).isEmpty();
    }

    @Test
    void firstElementOfArrayFromCallNeedsLengthCheck() {
        assertThat(RuleTests.lines(new ListGetFirstWithoutCheckRule(), """
                class StatisticService {
                    Statistic latest(Advert advert, boolean today) {
                        StatisticResponse[] response;
                        if (today) {
                            response = client.statisticByDates(advert.getId());
                        } else {
                            response = client.statisticByInterval(advert.getId());
                        }
                        StatisticResponse first = response[0];
                        return convert(first);
                    }
                    Update next() {
                        Update[] updates = client.updates();
                        return updates[0];
                    }
                }
                """)).containsExactly(9, 14);
    }

    @Test
    void firstElementOfCheckedOrSureArrayIsFine() {
        assertThat(RuleTests.lines(new ListGetFirstWithoutCheckRule(), """
                class Reader {
                    Update checked() {
                        Update[] updates = client.updates();
                        if (updates.length != 0) {
                            return updates[0];
                        }
                        return null;
                    }
                    Update exits() {
                        Update[] updates = client.updates();
                        if (ArrayUtils.isEmpty(updates)) {
                            return null;
                        }
                        return updates[0];
                    }
                    Class<?> generic(Object handler) {
                        Type[] arguments = ((ParameterizedType) handler.getClass().getGenericSuperclass())
                                .getActualTypeArguments();
                        return (Class<?>) arguments[0];
                    }
                    Object argument(ProceedingJoinPoint point) {
                        Object[] arguments = point.getArgs();
                        return arguments[0];
                    }
                    Status first() {
                        Status[] statuses = Status.values();
                        return statuses[0];
                    }
                    Update created() {
                        Update[] updates = new Update[] {new Update()};
                        return updates[0];
                    }
                    Update passed(Update[] updates) {
                        return updates[0];
                    }
                    // матрицу и массив, с которым работают по местам, создают с заданным размером
                    double[][] matrix(List<Rod> rods) {
                        double[][] matrix = MathUtil.fillZeros(rods.size() + 1);
                        matrix[0][0] = 1;
                        matrix[0][1] = 0;
                        return matrix;
                    }
                    Update pair() {
                        Update[] updates = client.pair();
                        merge(updates[0], updates[1]);
                        return updates[0];
                    }
                    void fill() {
                        Update[] holder = allocate();
                        holder[0] = new Update();
                    }
                }
                """)).isEmpty();
    }

    @Test
    void initializationVectorKeptInBeanFieldIsShared() {
        List<Violation> violations = RuleTests.check(new WeakCipherRule(), """
                @Service
                public class TokenEncryptionService {
                    private final byte[] keyBytes;
                    private final byte[] ivBytes;
                    public TokenEncryptionService(@Value("${crypt.key}") String key, @Value("${crypt.iv}") String iv) {
                        this.keyBytes = Hex.decode(key);
                        this.ivBytes = Hex.decode(iv);
                    }
                    public String encrypt(String text) {
                        ChaChaEngine engine = new ChaChaEngine();
                        engine.init(true, new ParametersWithIV(new KeyParameter(keyBytes), ivBytes));
                        return process(engine, text);
                    }
                    public byte[] gcm(byte[] data) throws Exception {
                        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                        cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, this.ivBytes));
                        return cipher.doFinal(data);
                    }
                }
                """);
        assertThat(violations).extracting(Violation::line).containsExactly(11, 16);
        assertThat(violations.get(0).message()).contains("'ivBytes'").contains("один на все сообщения");
    }

    @Test
    void freshOrPerMessageInitializationVectorIsFine() {
        assertThat(RuleTests.lines(new WeakCipherRule(), """
                @Service
                public class Encryptor {
                    private final SecureRandom random = new SecureRandom();
                    private byte[] lastIv;
                    public byte[] encrypt(byte[] data) throws Exception {
                        byte[] iv = new byte[12];
                        random.nextBytes(iv);
                        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                        cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
                        return cipher.doFinal(data);
                    }
                    public byte[] decrypt(byte[] message) throws Exception {
                        byte[] iv = Arrays.copyOfRange(message, 0, 12);
                        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
                        return cipher.doFinal(message, 12, message.length - 12);
                    }
                    public byte[] rotating(byte[] data) throws Exception {
                        lastIv = nextIv();
                        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                        cipher.init(Cipher.ENCRYPT_MODE, key(), new IvParameterSpec(lastIv));
                        return cipher.doFinal(data);
                    }
                }
                """)).isEmpty();
        // Объект на одно сообщение: вектор в поле у него свой
        assertThat(RuleTests.lines(new WeakCipherRule(), """
                public class EncryptedMessage {
                    private final byte[] iv;
                    public EncryptedMessage(SecureRandom random) {
                        this.iv = new byte[16];
                        random.nextBytes(this.iv);
                    }
                    public byte[] seal(byte[] data) throws Exception {
                        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                        cipher.init(Cipher.ENCRYPT_MODE, key(), new IvParameterSpec(iv));
                        return cipher.doFinal(data);
                    }
                }
                """)).isEmpty();
    }

    @Test
    void oneTimeCodeInLogIsFound() {
        List<Violation> violations = RuleTests.checkProject(new SensitiveDataLoggingRule(), Map.of(
                "LogTwoFactorNotificationSender.java", """
                        @Slf4j
                        public class LogTwoFactorNotificationSender implements TwoFactorNotificationSender {
                            public boolean sendAuthenticationCode(String login, String code) {
                                logger.info("Successful send authentication code = [{}] to login = [{}]", code, login);
                                return true;
                            }
                            public boolean sendProfileDeleteCode(String login, String code) {
                                logger.info("Successful send delete profile code = [{}] to login = [{}]", code, login);
                                return true;
                            }
                        }
                        """,
                "SmsService.java", """
                        @Slf4j
                        public class SmsService {
                            void send(String phone, int otp, String value) {
                                log.info("Sending {} to {}", otp, phone);
                                log.debug("verification code: {}", value);
                            }
                            void status(String phone, int statusCode, String errorCode, String otpLength) {
                                log.info("Gateway answered {} / {} for {}", statusCode, errorCode, phone);
                                log.info("status code = {}, error code = {}", statusCode, errorCode);
                                log.info("otp length = {}", otpLength);
                            }
                        }
                        """));
        assertThat(violations).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactlyInAnyOrder("LogTwoFactorNotificationSender.java:4",
                        "LogTwoFactorNotificationSender.java:8", "SmsService.java:4", "SmsService.java:5");
        assertThat(violations).allSatisfy(violation -> {
            assertThat(violation.errorLevel()).isEqualTo(ErrorLevel.MAJOR);
            assertThat(violation.message()).contains("одноразовый код");
        });
    }

    @Test
    void valueNamedSecretByMessageIsFound() {
        assertThat(RuleTests.checkProject(new SensitiveDataLoggingRule(), Map.of("Importer.java", """
                @Slf4j
                public class Importer {
                    void connect(String user, String value, Settings settings, boolean present) {
                        log.info("Connecting as {} with password = [{}]", user, value);
                        log.info("api key: '{}'", settings.getValue());
                        log.info("password set: {}", present);
                        log.info("password length = {}", value);
                        log.info("encrypted password = {}", value);
                        log.info("password = {}", mask(value));
                        log.info("Unexpected token {}", value);
                        log.info("Password changed for {}", user);
                    }
                }
                """))).extracting(Violation::line).containsExactly(4, 5);
    }

    @Test
    void valueReadElsewhereAsSecretIsFoundInLog() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("MessageDispatcher.java", """
                @Slf4j
                public class MessageDispatcher {
                    void dispatch(Payload payload) {
                        String text = payload.getMessage().getText();
                        logger.info("Received in dispatcher message = [{}]", text);
                        logger.debug("Chat = {}", payload.getMessage().getChatId());
                    }
                }
                """);
        files.put("InputTokenStateResolver.java", """
                public class InputTokenStateResolver {
                    void resolve(Payload payload) {
                        String token = payload.getMessage().getText();
                        clientService.updateToken(token);
                    }
                }
                """);
        List<Violation> violations = RuleTests.checkProject(new SensitiveDataLoggingRule(), files);
        assertThat(violations).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("MessageDispatcher.java:5");
        assertThat(violations.get(0).message()).contains("InputTokenStateResolver.java:3").contains("'token'");
        assertThat(violations.get(0).errorLevel()).isEqualTo(ErrorLevel.MAJOR);
        assertThat(violations.get(0).confidenceOrDefault()).isNotEqualTo(Confidence.CONFIRMED);
    }

    @Test
    void sameGetterOnAnotherVariableOrCarrierIsNotSecretRead() {
        assertThat(RuleTests.checkProject(new SensitiveDataLoggingRule(), Map.of("LoginForm.java", """
                @Slf4j
                public class LoginForm {
                    void submit(TextField passwordField, TextField nameField, Map.Entry<String, String> entry) {
                        String password = passwordField.getText();
                        String secret = entry.getValue();
                        log.info("Name = {}", nameField.getText());
                        log.info("Entry = {}", entry.getValue());
                        auth(password, secret);
                    }
                }
                """))).isEmpty();
    }

    private static final String OPEN_ACTUATOR = """
            @Configuration
            public class SecurityConfig {
                @Bean
                public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
                    http.authorizeHttpRequests(auth -> auth
                            .requestMatchers("/security/two/factor/**")
                            .permitAll()
                            .requestMatchers("/actuator/**")
                            .permitAll()
                            .anyRequest()
                            .authenticated());
                    return http.build();
                }
            }
            """;

    @Test
    void actuatorOpenToEveryoneIsFoundByWhatIsExposed(@TempDir Path projects) {
        List<Violation> metrics = new ProjectFixture(projects.resolve("metrics"))
                .source("SecurityConfig.java", OPEN_ACTUATOR)
                .write("src/main/resources/application.properties",
                        "management.endpoints.web.exposure.include=health,prometheus\n")
                .check(new ActuatorExposureRule());
        assertThat(metrics).extracting(violation -> violation.file().getFileName() + ":" + violation.line()
                + " " + violation.errorLevel()).containsExactly("SecurityConfig.java:8 MINOR");
        assertThat(metrics.get(0).message()).contains("выставляет по HTTP prometheus:");

        List<Violation> internals = new ProjectFixture(projects.resolve("internals"))
                .source("SecurityConfig.java", OPEN_ACTUATOR)
                .write("src/main/resources/application.properties",
                        "management.endpoints.web.exposure.include=health,env,loggers\n")
                .check(new ActuatorExposureRule());
        assertThat(internals).extracting(Violation::errorLevel).containsExactly(ErrorLevel.MAJOR);
    }

    @Test
    void actuatorOpenToEveryoneIsFineWhenOnlyHealthIsExposed(@TempDir Path projects) {
        assertThat(new ProjectFixture(projects.resolve("default"))
                .source("SecurityConfig.java", OPEN_ACTUATOR)
                .write("src/main/resources/application.properties", "server.port=8080\n")
                .check(new ActuatorExposureRule())).isEmpty();
        assertThat(new ProjectFixture(projects.resolve("health"))
                .source("SecurityConfig.java", OPEN_ACTUATOR)
                .write("src/main/resources/application.properties",
                        "management.endpoints.web.exposure.include=health,info\n")
                .check(new ActuatorExposureRule())).isEmpty();
        // Открыт один названный адрес: это решение, а не правило на все дерево
        assertThat(new ProjectFixture(projects.resolve("named"))
                .source("SecurityConfig.java", OPEN_ACTUATOR.replace("/actuator/**", "/actuator/prometheus"))
                .write("src/main/resources/application.properties",
                        "management.endpoints.web.exposure.include=health,prometheus\n")
                .check(new ActuatorExposureRule())).isEmpty();
    }

    private static final String RATE_LIMITED = """
            import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
            @Service
            public class MarketClient {
                @RateLimiter(name = "cards")
                public List<Card> cards(String token) {
                    return restTemplate.getForObject(url, CardList.class).getCards();
                }
                @RateLimiter(name = "adverts")
                public List<Advert> adverts(String token) {
                    return restTemplate.getForObject(url, AdvertList.class).getAdverts();
                }
            }
            """;

    private static String pom(String bootVersion, String starter) {
        return """
                <project>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>%s</version>
                    </parent>
                    <dependencies>
                        <dependency>
                            <groupId>io.github.resilience4j</groupId>
                            <artifactId>%s</artifactId>
                            <version>1.7.0</version>
                        </dependency>
                        <dependency>
                            <groupId>io.github.resilience4j</groupId>
                            <artifactId>resilience4j-circuitbreaker</artifactId>
                            <version>1.7.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """.formatted(bootVersion, starter);
    }

    @Test
    void resilienceAnnotationsAreInertWithStarterForAnotherBoot(@TempDir Path projects) {
        List<Violation> violations = new ProjectFixture(projects.resolve("boot3"))
                .source("MarketClient.java", RATE_LIMITED)
                .write("pom.xml", pom("3.3.1", "resilience4j-spring-boot2"))
                .check(new MissingEnableAnnotationRule());
        assertThat(violations).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("MarketClient.java:4");
        assertThat(violations.get(0).message())
                .contains("@RateLimiter", "resilience4j-spring-boot3", "таких методов в проекте: 2");
        assertThat(violations.get(0).errorLevel()).isEqualTo(ErrorLevel.MAJOR);
    }

    @Test
    void resilienceAnnotationsWorkWithMatchingStarterOrOwnAspects(@TempDir Path projects) {
        assertThat(new ProjectFixture(projects.resolve("matching"))
                .source("MarketClient.java", RATE_LIMITED)
                .write("pom.xml", pom("3.3.1", "resilience4j-spring-boot3"))
                .check(new MissingEnableAnnotationRule())).isEmpty();
        assertThat(new ProjectFixture(projects.resolve("boot2"))
                .source("MarketClient.java", RATE_LIMITED)
                .write("pom.xml", pom("2.7.18", "resilience4j-spring-boot2"))
                .check(new MissingEnableAnnotationRule())).isEmpty();
        assertThat(new ProjectFixture(projects.resolve("own"))
                .source("MarketClient.java", RATE_LIMITED)
                .source("ResilienceConfig.java", """
                        @Configuration
                        public class ResilienceConfig {
                            @Bean
                            public RateLimiterAspect rateLimiterAspect(RateLimiterRegistry registry) {
                                return new RateLimiterAspect(registry, properties(), List.of(), fallback(), spel());
                            }
                        }
                        """)
                .write("pom.xml", pom("3.3.1", "resilience4j-spring-boot2"))
                .check(new MissingEnableAnnotationRule())).isEmpty();
    }

    @Test
    void generalHandlerAnsweringBadRequestHidesServerFailures() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("ApiExceptionHandler.java", """
                @RestControllerAdvice
                public class ApiExceptionHandler {
                    @ExceptionHandler({Exception.class})
                    public ResponseEntity<ErrorResponse> handleException(Exception exception) {
                        ErrorResponse response = new ErrorResponse("general.error", exception.getMessage());
                        return new ResponseEntity<>(response, new HttpHeaders(), HttpStatus.BAD_REQUEST);
                    }
                    @ExceptionHandler(MethodArgumentNotValidException.class)
                    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
                        return ResponseEntity.badRequest().body(new ErrorResponse(exception.getMessage()));
                    }
                }
                """);
        files.put("CarefulHandler.java", """
                @RestControllerAdvice
                public class CarefulHandler {
                    @ExceptionHandler(Exception.class)
                    public ResponseEntity<ErrorResponse> handle(Exception exception) {
                        if (exception instanceof IllegalArgumentException) {
                            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error(exception));
                        }
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(exception));
                    }
                }
                """);
        List<Violation> violations = RuleTests.checkProject(new BusinessErrorAsBadRequestRule(), files);
        assertThat(violations).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("ApiExceptionHandler.java:6");
        assertThat(violations.get(0).errorLevel()).isEqualTo(ErrorLevel.MAJOR);
        assertThat(violations.get(0).message()).contains("5xx");
    }

    @Test
    void beanThatNobodyUsesAndClassUsedOnlyByTestsAreFound() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/main/java/app/Application.java", """
                @SpringBootApplication
                public class Application {
                    public static void main(String[] args) {
                        SpringApplication.run(Application.class, args);
                    }
                }
                """);
        files.put("src/main/java/app/TransactionHelper.java", """
                @Component
                public class TransactionHelper {
                    @SneakyThrows
                    @Transactional
                    public <T> T execute(Callable<T> action) {
                        return action.call();
                    }
                }
                """);
        files.put("src/main/java/app/CleanupJob.java", """
                @Component
                public class CleanupJob {
                    @Scheduled(fixedDelay = 60_000)
                    public void clean() {
                    }
                }
                """);
        files.put("src/main/java/app/AuditFilter.java", """
                @Component
                public class AuditFilter extends MessageFilter {
                    public void handle(Message message) {
                    }
                }
                """);
        files.put("src/main/java/app/NamedExporter.java", """
                @Service("csvExporter")
                public class NamedExporter {
                    public void export() {
                    }
                }
                """);
        files.put("src/main/java/app/OrderService.java", """
                @Service
                public class OrderService {
                    public void create() {
                    }
                }
                """);
        files.put("src/main/java/app/OrderController.java", """
                @RestController
                public class OrderController {
                    private final OrderService orderService;
                    @PostMapping("/orders")
                    public void create() {
                        orderService.create();
                    }
                }
                """);
        files.put("src/main/java/app/http/HttpClientBuilderFactory.java", """
                public class HttpClientBuilderFactory {
                    public CommonHttpClientBuilder builder() {
                        return new CommonHttpClientBuilder();
                    }
                }
                """);
        files.put("src/main/java/app/http/CommonHttpClientBuilder.java", """
                public class CommonHttpClientBuilder {
                    public RestTemplate build() {
                        return new RestTemplate();
                    }
                }
                """);
        files.put("src/main/java/app/http/MockClock.java", """
                public class MockClock {
                    public long now() {
                        return 0;
                    }
                }
                """);
        files.put("src/test/java/app/http/HttpClientTest.java", """
                class HttpClientTest {
                    @Test
                    void builds() {
                        assertThat(new HttpClientBuilderFactory().builder().build()).isNotNull();
                        assertThat(new MockClock().now()).isZero();
                    }
                }
                """);
        List<Violation> violations = RuleTests.checkProject(new UnusedClassRule(), files);
        // О группе классов, нужных только тестам, сказано один раз - на классе, с которого тест в нее входит
        assertThat(violations).extracting(violation -> violation.file().getFileName().toString())
                .containsExactlyInAnyOrder("TransactionHelper.java", "HttpClientBuilderFactory.java");
        assertThat(violations).allSatisfy(violation ->
                assertThat(violation.confidenceOrDefault()).isEqualTo(Confidence.PROBABLE));
        assertThat(messageOf(violations, "TransactionHelper.java")).contains("создается контейнером");
        assertThat(messageOf(violations, "HttpClientBuilderFactory.java"))
                .contains("только в тестах (из HttpClientTest)", "(1): CommonHttpClientBuilder.");
    }

    @Test
    void classesReachedByNameAreNotCalledUnused() {
        // Программа с обычным main: расширения в ней подключают по имени, и ссылка только из теста ничего не значит
        Map<String, String> program = new LinkedHashMap<>();
        program.put("src/main/java/app/Main.java", """
                public class Main {
                    public static void main(String[] args) {
                        ExtensionLoader.loadAll().forEach(Runnable::run);
                    }
                }
                """);
        program.put("src/main/java/app/ExtensionLoader.java", """
                public class ExtensionLoader {
                    public static List<Runnable> loadAll() {
                        return ServiceLoader.load(Runnable.class).stream().map(ServiceLoader.Provider::get).toList();
                    }
                }
                """);
        program.put("src/main/java/app/ReportExtension.java", """
                public class ReportExtension implements Runnable {
                    public void run() {
                    }
                }
                """);
        program.put("src/test/java/app/ReportExtensionTest.java", """
                class ReportExtensionTest {
                    @Test
                    void runs() {
                        new ReportExtension().run();
                    }
                }
                """);
        assertThat(RuleTests.checkProject(new UnusedClassRule(), program)).isEmpty();

        // Бины берут по имени, которого в коде нет: задачу планировщика называют в базе
        Map<String, String> jobs = new LinkedHashMap<>();
        jobs.put("src/main/java/app/Application.java", """
                @SpringBootApplication
                public class Application {
                    public static void main(String[] args) {
                        SpringApplication.run(Application.class, args);
                    }
                }
                """);
        jobs.put("src/main/java/app/JobRunner.java", """
                @Component
                public class JobRunner {
                    private final ApplicationContext context;
                    @Scheduled(fixedDelay = 1000)
                    public void run() {
                        for (JobRecord job : jobs.findDue()) {
                            Object target = context.getBean(job.getBeanName());
                            ReflectionUtils.invokeMethod(methodOf(target, job.getMethodName()), target);
                        }
                    }
                }
                """);
        jobs.put("src/main/java/app/CleanupTask.java", """
                @Component
                public class CleanupTask {
                    public void run() {
                    }
                }
                """);
        assertThat(RuleTests.checkProject(new UnusedClassRule(), jobs)).isEmpty();
    }

    private static String messageOf(List<Violation> violations, String file) {
        return violations.stream()
                .filter(violation -> violation.file().getFileName().toString().equals(file))
                .map(Violation::message)
                .findFirst()
                .orElse("");
    }

    @Test
    void propertyKeptOnlyInIgnoredFileIsMoreThanInformation(@TempDir Path projects) {
        String service = """
                @Service
                public class OtpService {
                    @Value("${security.otp.length}")
                    private int length;
                    @Value("${security.otp.lifetime}")
                    private int lifetime;
                    @Value("${server.port}")
                    private int port;
                }
                """;
        List<Violation> violations = new ProjectFixture(projects.resolve("ignored"))
                .source("OtpService.java", service)
                .write(".gitignore", "target/\napplication-*.properties\n!application-prod.properties\n")
                .write("src/main/resources/application.properties", "server.port=8080\n")
                .write("src/main/resources/application-local.properties", "security.otp.length=6\n")
                .write("src/main/resources/application-prod.properties", "security.otp.lifetime=60\n")
                .check(new ValueWithoutDefaultRule());
        assertThat(violations).extracting(violation -> violation.line() + " " + violation.errorLevel())
                .containsExactly("3 MINOR", "5 INFO");
        assertThat(violations.get(0).message()).contains("application-local.properties", ".gitignore", "чистой копии");
    }

    @Test
    void relationFetchedByQueryOfTheListIsNotLoadedPerElement(@TempDir Path projects) {
        ProjectFixture project = new ProjectFixture(projects.resolve("adverts"))
                .source("AdvertEntity.java", """
                        @Entity
                        class AdvertEntity {
                            @ManyToOne(fetch = FetchType.LAZY)
                            private CardEntity card;
                        }
                        """)
                .source("CardEntity.java", """
                        @Entity
                        class CardEntity {
                            @ManyToOne(fetch = FetchType.LAZY)
                            private ClientEntity ownerClient;
                        }
                        """)
                .source("AdvertRepository.java", """
                        interface AdvertRepository extends JpaRepository<AdvertEntity, Long> {
                            @Query("from AdvertEntity ae join fetch ae.card aec join fetch aec.ownerClient "
                                    + "where ae.status in :statuses")
                            List<AdvertEntity> findByStatuses(@Param("statuses") List<AdvertStatus> statuses);
                            List<AdvertEntity> findByDeletedFalse();
                        }
                        """)
                .source("CheckJob.java", """
                        @Component
                        class CheckJob {
                            private final AdvertRepository advertRepository;
                            void fetched() {
                                List<AdvertEntity> running = advertRepository.findByStatuses(RUNNING);
                                for (AdvertEntity advert : running) {
                                    send(advert.getCard().getOwnerClient().getToken());
                                }
                            }
                            void lazy() {
                                List<AdvertEntity> all = advertRepository.findByDeletedFalse();
                                for (AdvertEntity advert : all) {
                                    send(advert.getCard().getBarcode());
                                }
                            }
                        }
                        """);
        assertThat(project.lines(new LazyRelationInMappingRule())).containsExactly("CheckJob.java:13");
    }
}
