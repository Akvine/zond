package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ConfigProperty;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.TestWithoutAssertionRule;
import ru.akvine.zond.rules.logical.DdlAutoRule;
import ru.akvine.zond.rules.logical.DuplicateEndpointRule;
import ru.akvine.zond.rules.logical.PossibleNullDereferenceRule;
import ru.akvine.zond.rules.logical.QueryParameterMismatchRule;
import ru.akvine.zond.rules.logical.TransactionalRollbackForCheckedExceptionRule;
import ru.akvine.zond.rules.performance.RepositoryCallInLoopRule;
import ru.akvine.zond.rules.resources.UnclosedResourceRule;
import ru.akvine.zond.rules.security.ActuatorExposureRule;
import ru.akvine.zond.rules.security.HardcodedCredentialsRule;
import ru.akvine.zond.rules.security.PathTraversalRule;
import ru.akvine.zond.rules.security.SecretInConfigRule;
import ru.akvine.zond.rules.security.SensitiveDataLoggingRule;
import ru.akvine.zond.rules.security.SsrfRule;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Случаи из промышленного кода, на которых правила раньше срабатывали зря: каждый тест - один такой класс
 * случаев и рядом пример, где находка должна остаться
 */
class PrecisionRulesTest {

    @Test
    void singleValueChainIsNotLoop() {
        // У Mono лямбда выполняется не больше одного раза; у Flux и стрима - на каждый элемент
        assertThat(RuleTests.lines(new RepositoryCallInLoopRule(), """
                class PostFinder {
                    private PostRepository postRepository;
                    Mono<Post> previous(String name) {
                        return client.fetch(name)
                                .filter(post -> post.isPublished())
                                .flatMap(post -> postRepository.findByName(post.getPrevious()));
                    }
                    Mono<List<Post>> related(Mono<Post> current) {
                        return current.map(post -> postRepository.findAllByTag(post.getTag()));
                    }
                    Mono<Integer> counted(Flux<Post> posts) {
                        return posts.collectList().map(list -> postRepository.countByAuthor(list.size()));
                    }
                    Flux<Post> authors(Flux<Post> posts) {
                        return posts.flatMap(post -> postRepository.findByName(post.getAuthor()));
                    }
                    List<Post> blocking(List<String> names) {
                        return names.stream().map(name -> postRepository.findByName(name)).toList();
                    }
                }
                """)).containsExactly(15, 18);
    }

    @Test
    void pathIsBuiltFromRequestOnlyWhereClientControlsIt() {
        assertThat(RuleTests.lines(new PathTraversalRule(), """
                @RestController
                class Files {
                    @GetMapping("/plugin")
                    Object plugin(@RequestParam String name) {
                        return dependencyResolver.resolve(name);
                    }
                    @PostMapping("/avatar")
                    void avatar(@RequestParam MultipartFile file) throws IOException {
                        String original = file.getOriginalFilename();
                        String suffix = original.substring(original.lastIndexOf("."));
                        File target = new File(UPLOAD_DIR + UUID.randomUUID() + suffix);
                        file.transferTo(target);
                    }
                    @GetMapping("/checked")
                    byte[] checked(@RequestParam String name) throws IOException {
                        ensureDownloadable(name);
                        return java.nio.file.Files.readAllBytes(Paths.get(BASE, name));
                    }
                    private void ensureDownloadable(String name) {
                        if (name.contains("..")) {
                            throw new IllegalArgumentException(name);
                        }
                    }
                    @GetMapping("/raw")
                    byte[] raw(@RequestParam String name) throws IOException {
                        return java.nio.file.Files.readAllBytes(uploadDir.resolve(name));
                    }
                }
                """)).containsExactly(26);
    }

    @Test
    void addressIsTakenFromRequestOnlyWhenClientSetsHost() {
        List<Violation> violations = RuleTests.check(new SsrfRule(), """
                @RestController
                class Proxy {
                    @GetMapping("/origin")
                    boolean sameOrigin(@RequestHeader("Origin") String origin) throws Exception {
                        return new URI(origin).getHost().equals(HOST);
                    }
                    @GetMapping("/valid")
                    boolean valid(@RequestParam String link) throws Exception {
                        URL url = new URL(link);
                        url.toURI();
                        return true;
                    }
                    @GetMapping("/lookup")
                    String lookup(@RequestParam String ip) {
                        return restTemplate.getForObject(lookupUrl + "?ip=" + ip, String.class);
                    }
                    @GetMapping("/file")
                    String file(@RequestParam String key) {
                        return restTemplate.getForObject(storageUrl + key, String.class);
                    }
                    @GetMapping("/fetch")
                    String fetch(@RequestParam String host) {
                        return restTemplate.getForObject("https://" + host + "/status", String.class);
                    }
                }
                """);

        // Разбор адреса и данные после "?" - не обращение по адресу клиента; начало адреса из настроек без "/" -
        // подозрение; "https://" + host - узел задает клиент
        assertThat(violations).extracting(violation -> violation.line() + " " + violation.confidenceOrDefault())
                .containsExactly("19 " + Confidence.SUSPICION, "23 " + Confidence.CONFIRMED);
    }

    @Test
    void namesAndPlaceholdersAreNotSecrets() {
        List<Violation> violations = RuleTests.check(new HardcodedCredentialsRule(), """
                class Constants {
                    static final String ACTION_SET_CREDENTIALS = "formBasedAuthenticationCredentials";
                    static final String HEADER_TOKEN = "X-Auth-Token";
                    static final String DEFAULT_PASSWORD = "false";
                    static final String LOCALE_TOKEN = "%LOCALE%";
                    static final String USER_TOKEN = "ApolloUserToken";
                    static final String API_TOKEN = "9f8a7c6b5d4e3f2a1b0c";
                    UserDetails anonymous() {
                        return User.builder().username("guest").password("{noop}").build();
                    }
                    UserDetails hashed() {
                        return User.builder().username("admin").password("{bcrypt}$2a$10$N9qo8uLOickgx2ZM").build();
                    }
                    UserDetails plain() {
                        return User.builder().username("admin").password("{noop}Winter2024").build();
                    }
                }
                """);

        // Название действия или заголовка, значение настройки, шаблон, пустой пароль и хеш - не секреты.
        // Значение из одних слов (ApolloUserToken) похоже на название: это подозрение
        assertThat(violations).extracting(violation -> violation.line() + " " + violation.confidenceOrDefault())
                .containsExactly("6 " + Confidence.SUSPICION, "7 " + Confidence.PROBABLE, "15 " + Confidence.PROBABLE);
    }

    @Test
    void secretsInConfigRespectProfilesAndDefaults() {
        SecretInConfigRule rule = new SecretInConfigRule();
        ConfigFile main = new ConfigFile(Path.of("application.yml"), List.of(
                new ConfigProperty("spring.rabbitmq.username", "guest", 1),
                new ConfigProperty("spring.rabbitmq.password", "guest", 2),
                new ConfigProperty("spring.datasource.username", "shop", 3),
                new ConfigProperty("spring.datasource.password", "shop", 4),
                new ConfigProperty("app.payment.api-key", "sk_live_51Hb8QxL2eZvKYlo2C", 5),
                new ConfigProperty("spring.config.activate.on-profile", "dev", 7),
                new ConfigProperty("app.mail.password", "Tr0ub4dor&3", 8)), Set.of(7, 8));

        // Общеизвестное значение и пароль, равный имени пользователя, - подозрение; документ профиля dev не в счет
        assertThat(rule.checkConfig(main))
                .extracting(violation -> violation.line() + " " + violation.confidenceOrDefault())
                .containsExactly("2 " + Confidence.SUSPICION, "4 " + Confidence.SUSPICION, "5 " + Confidence.PROBABLE);

        List<ConfigProperty> secret = List.of(new ConfigProperty("app.mail.password", "Tr0ub4dor&3", 1));
        for (String name : List.of("application-dev.yml", "application-local-h2.properties", "application-ci.yml",
                "application-secret-samples.yml", "config/dev/application.yml", "src/test/resources/application.yml")) {
            assertThat(rule.checkConfig(new ConfigFile(Path.of(name), secret))).isEmpty();
        }
        for (String name : List.of("application.yml", "application-prod.yml", "application-prod-debug.yml",
                "application-docker.yml")) {
            assertThat(rule.checkConfig(new ConfigFile(Path.of(name), secret))).hasSize(1);
        }
    }

    @Test
    void profileExpressions() {
        assertThat(ConfigFile.isNonProductionProfile("dev")).isTrue();
        assertThat(ConfigFile.isNonProductionProfile("dev | local")).isTrue();
        assertThat(ConfigFile.isNonProductionProfile("!prod")).isTrue();
        assertThat(ConfigFile.isNonProductionProfile("prod")).isFalse();
        assertThat(ConfigFile.isNonProductionProfile("dev, prod")).isFalse();
        assertThat(ConfigFile.isNonProductionProfile("!dev")).isFalse();
        assertThat(ConfigFile.isNonProductionProfile("")).isFalse();
    }

    @Test
    void queryParametersOfOtherStores() {
        // MongoDB нумерует параметры с ?0, а двоеточие в JSON и в Cypher - не имя параметра
        assertThat(RuleTests.lines(new QueryParameterMismatchRule(), """
                interface PersonRepository extends ReactiveMongoRepository<Person, String> {
                    @Query("{ 'firstname': ?0, 'lastname': ?1 }")
                    Flux<Person> findByNames(String firstname, String lastname);
                    @Query("{ 'firstname': ?0, 'lastname': ?2 }")
                    Flux<Person> broken(String firstname, String lastname);
                    @Query("MATCH (p:Person)-[:KNOWS]->(friend:Person) WHERE p.name = $name RETURN friend")
                    List<Person> friends(@Param("name") String name);
                    @Query("select p from Person p where p.name = ?1 and p.age > ?2")
                    List<Person> jpa(String name);
                }
                """)).containsExactly(4, 8);
    }

    @Test
    void endpointsThatDoNotClash() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("OttLoginController.java", """
                @Controller
                class OttLoginController {
                    static final String PATH = "/auth/ott";
                    @GetMapping(PATH)
                    String page() { return "ott"; }
                }
                """);
        files.put("FormLoginController.java", """
                @Controller
                class FormLoginController {
                    static final String PATH = "/auth/form";
                    @GetMapping(PATH)
                    String page() { return "form"; }
                }
                """);
        files.put("GreetingController.java", """
                @RestController
                class GreetingController {
                    @GetMapping("/hello")
                    String hello() { return "v1"; }
                    @GetMapping(value = "/hello", version = "1.1")
                    String helloNext() { return "v1.1"; }
                    @GetMapping("/bye")
                    String bye() { return "bye"; }
                    @GetMapping("/bye")
                    String byeAgain() { return "bye"; }
                }
                """);
        files.put("MockPaymentController.java", """
                @RestController
                @Profile("mock")
                class MockPaymentController {
                    @PostMapping("/pay")
                    String pay() { return "ok"; }
                }
                """);
        files.put("PaymentController.java", """
                @RestController
                class PaymentController {
                    @PostMapping("/pay")
                    String pay() { return "paid"; }
                }
                """);

        // У каждого класса своя константа PATH; версия API и профиль разводят обработчики с одним адресом
        assertThat(RuleTests.checkProject(new DuplicateEndpointRule(), files))
                .extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("GreetingController.java:9");
    }

    @Test
    void rollbackNeedsCheckedException() {
        List<Violation> violations = RuleTests.check(new TransactionalRollbackForCheckedExceptionRule(), """
                class Users {
                    @Transactional
                    public UserDetails loadUserByUsername(String name) throws UsernameNotFoundException {
                        return null;
                    }
                    @Transactional
                    public void export(String name) throws IOException {
                    }
                    @Transactional
                    public void publish(String name) throws PublicationFailure {
                    }
                    @SneakyThrows
                    @Transactional
                    public void execute(Runnable task) {
                        task.run();
                    }
                    @SneakyThrows
                    @Transactional
                    public <R> R call(Callable<R> task) {
                        return task.call();
                    }
                }
                """);

        // UsernameNotFoundException - наследник RuntimeException; о PublicationFailure ничего не известно;
        // Runnable.run() проверяемых исключений не бросает
        assertThat(violations).extracting(violation -> violation.line() + " " + violation.confidenceOrDefault())
                .containsExactly("6 " + Confidence.PROBABLE, "9 " + Confidence.SUSPICION, "17 " + Confidence.PROBABLE);
    }

    @Test
    void resourceClosedByItsOwner() {
        assertThat(RuleTests.lines(new UnclosedResourceRule(), """
                class Server {
                    void handle(ServerSocket server) throws IOException {
                        try (Socket client = server.accept()) {
                            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
                            reader.readLine();
                        }
                    }
                    Optional<String> find(DataSource dataSource, String name) throws SQLException {
                        try (Connection connection = dataSource.getConnection();
                             PreparedStatement statement = connection.prepareStatement("select name from product")) {
                            ResultSet rows = statement.executeQuery();
                            return rows.next() ? Optional.of(rows.getString(1)) : Optional.empty();
                        }
                    }
                    void write(HttpServletResponse response, String text) throws IOException {
                        PrintWriter writer = new PrintWriter(new OutputStreamWriter(response.getOutputStream()));
                        writer.print(text);
                    }
                    String leak(File file) throws IOException {
                        BufferedReader reader = new BufferedReader(new FileReader(file));
                        return reader.readLine();
                    }
                }
                """)).containsExactly(20);
    }

    @Test
    void valueUsedUnderConditionOfItsAssignment() {
        assertThat(RuleTests.lines(new PossibleNullDereferenceRule(), """
                class Commands {
                    void send(Position position, Command command) {
                        Protocol protocol = position != null ? manager.getProtocol(position) : null;
                        if (position != null) {
                            protocol.send(command);
                        }
                    }
                    void broken(Position position, Command command) {
                        Protocol protocol = position != null ? manager.getProtocol(position) : null;
                        if (command != null) {
                            protocol.send(command);
                        }
                    }
                }
                """)).containsExactly(11);
    }

    @Test
    void assertionPassedAsMethodReference() {
        List<Violation> violations = RuleTests.check(new TestWithoutAssertionRule(), """
                class HandlerTest {
                    @Test
                    void checksByCallback() {
                        handler.analyze(position, Assertions::assertNull);
                    }
                    @Test
                    void onlyRuns() {
                        handler.analyze(position, event -> { });
                    }
                    @Test
                    void nothing() {
                    }
                }
                """);

        // Тест, который запускает код и ничего не сравнивает, проверяет лишь "не падает": уверенность ниже.
        // У пустого теста своей уверенности нет - действует уверенность правила ("подтверждено")
        assertThat(violations).extracting(violation -> violation.line() + " " + violation.confidence())
                .containsExactly("6 " + Confidence.PROBABLE, "10 null");
        assertThat(new TestWithoutAssertionRule().confidence()).isEqualTo(Confidence.CONFIRMED);
    }

    @Test
    void securityObjectInLogIsNotSecret() {
        List<Violation> violations = RuleTests.check(new SensitiveDataLoggingRule(), """
                class Login {
                    private static final Logger log = LoggerFactory.getLogger(Login.class);
                    void authenticate(UsernamePasswordAuthenticationToken usernamePasswordAuthenticationToken,
                                      AccessKey accessKey, String password) {
                        log.debug("Authenticating {}", usernamePasswordAuthenticationToken);
                        log.info("Key replaced: {}", accessKey);
                        log.info("Password is {}", password);
                    }
                }
                """);

        // Объект Spring Security себя не раскрывает; свой объект - как решит его toString(); строка - как есть
        assertThat(violations).extracting(violation -> violation.line() + " " + violation.confidenceOrDefault())
                .containsExactly("6 " + Confidence.SUSPICION, "7 " + Confidence.PROBABLE);
    }

    @Test
    void configRulesKnowInMemoryDatabaseAndManagementPort() {
        ConfigFile inMemory = new ConfigFile(Path.of("application.properties"), List.of(
                new ConfigProperty("spring.datasource.url", "jdbc:h2:mem:shop;DB_CLOSE_DELAY=-1", 1),
                new ConfigProperty("spring.jpa.hibernate.ddl-auto", "create-drop", 2)));
        ConfigFile postgres = new ConfigFile(Path.of("application.properties"), List.of(
                new ConfigProperty("spring.datasource.url", "jdbc:postgresql://db/shop", 1),
                new ConfigProperty("spring.jpa.hibernate.ddl-auto", "update", 2)));
        assertThat(new DdlAutoRule().checkConfig(inMemory)).isEmpty();
        assertThat(new DdlAutoRule().checkConfig(postgres)).extracting(Violation::line).containsExactly(2);

        ConfigFile open = new ConfigFile(Path.of("application.properties"), List.of(
                new ConfigProperty("management.endpoints.web.exposure.include", "*", 1)));
        ConfigFile separated = new ConfigFile(Path.of("application.properties"), List.of(
                new ConfigProperty("management.endpoints.web.exposure.include", "*", 1),
                new ConfigProperty("management.server.port", "9090", 2)));
        assertThat(new ActuatorExposureRule().checkConfig(open))
                .extracting(Violation::confidenceOrDefault).containsExactly(Confidence.PROBABLE);
        assertThat(new ActuatorExposureRule().checkConfig(separated))
                .extracting(Violation::confidenceOrDefault).containsExactly(Confidence.SUSPICION);
    }
}
