package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.logical.BeanDeclaredWiderThanInjectedRule;
import ru.akvine.zond.rules.logical.ConfigRefersToUntrackedFileRule;
import ru.akvine.zond.rules.logical.DockerJavaOlderThanBuildRule;
import ru.akvine.zond.rules.logical.PropertyValueWithoutConditionRule;
import ru.akvine.zond.rules.logical.QualifierWithoutBeanRule;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jr:541, jr:542, jr:544 - jr:546: приложение, которое не поднимается или молча запускается без своей части -
 * бин не находится, значение настройки ничего не включает, файла из настроек нет в хранилище, образ со старой Java
 */
class WiringAndStartupRulesTest {
    private static final String CONSTANTS = """
            public class ProxyConstants {
                public static final String ORIGIN_BEAN_NAME = "origin";
            }
            """;
    private static final String EMULATOR = """
            @Service
            @Profile("local")
            @Qualifier(ProxyConstants.ORIGIN_BEAN_NAME)
            public class MarketServiceEmulator implements MarketService {
            }
            """;
    private static final String PROXY_CONFIG = """
            @Configuration
            public class ProxyConfig {
                @Bean
                @Primary
                public MarketService marketService(
                        @Qualifier(ProxyConstants.ORIGIN_BEAN_NAME) MarketService target, ProxiesFacade proxies) {
                    return proxies.wrap(target);
                }
            }
            """;

    private static Map<String, String> market(String origin) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("ProxyConstants.java", CONSTANTS);
        files.put("MarketService.java", "public interface MarketService {\n}\n");
        files.put("MarketServiceEmulator.java", EMULATOR);
        files.put("MarketServiceOrigin.java", origin);
        files.put("ProxyConfig.java", PROXY_CONFIG);
        return files;
    }

    @Test
    void qualifierWhoseBeanExistsOnlyUnderProfileIsFound() {
        List<Violation> violations = RuleTests.checkProject(new QualifierWithoutBeanRule(), market("""
                @Service
                @Profile("!local")
                public class MarketServiceOrigin implements MarketService {
                }
                """));
        assertThat(violations).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("ProxyConfig.java:6");
        assertThat(violations.get(0).message())
                .contains("@Qualifier(\"origin\")", "профилем local", "MarketServiceEmulator", "MarketServiceOrigin");
    }

    @Test
    void qualifierWithBeanForEveryProfileIsFine() {
        // Квалификатор есть у обоих бинов: профили дополняют друг друга
        assertThat(RuleTests.checkProject(new QualifierWithoutBeanRule(), market("""
                @Service
                @Profile("!local")
                @Qualifier(ProxyConstants.ORIGIN_BEAN_NAME)
                public class MarketServiceOrigin implements MarketService {
                }
                """))).isEmpty();
        // Бин без профиля, которого находят по имени
        assertThat(RuleTests.checkProject(new QualifierWithoutBeanRule(), market("""
                @Service("origin")
                public class MarketServiceOrigin implements MarketService {
                }
                """))).isEmpty();
    }

    @Test
    void qualifierIsNotJudgedWhenBeanIsUnknownOrInjectionIsConditional() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Consumers.java", """
                @Service
                public class Consumers {
                    // бин приходит из библиотеки: в проекте его нет вовсе
                    public Consumers(@Qualifier("taskExecutor") Executor executor) {
                    }
                }
                """);
        files.put("LocalOnly.java", """
                @Configuration
                @Profile("local")
                public class LocalOnly {
                    @Bean
                    public Reporter reporter(@Qualifier("fake") Clock clock) {
                        return new Reporter(clock);
                    }
                    @Bean
                    @Qualifier("fake")
                    public Clock fakeClock() {
                        return Clock.systemUTC();
                    }
                }
                """);
        files.put("Optional.java", """
                @Service
                public class OptionalConsumer {
                    @Autowired(required = false)
                    @Qualifier("fake")
                    private Clock clock;
                    public OptionalConsumer(@Qualifier("fake") ObjectProvider<Clock> clocks) {
                    }
                }
                """);
        files.put("Conditional.java", """
                @Configuration
                public class Conditional {
                    @Bean
                    @Qualifier("remote")
                    @ConditionalOnProperty(name = "storage.type", havingValue = "s3")
                    public Storage s3Storage() {
                        return new S3Storage();
                    }
                    @Bean
                    public Uploader uploader(@Qualifier("remote") Storage storage) {
                        return new Uploader(storage);
                    }
                }
                """);
        assertThat(RuleTests.checkProject(new QualifierWithoutBeanRule(), files)).isEmpty();
    }

    @Test
    void beanInjectedByClassButDeclaredByInterfaceIsFound() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("TelegramBotConfig.java", """
                @Configuration
                public class TelegramBotConfig {
                    @Bean
                    @ConditionalOnProperty(name = "telegram.bot.type", havingValue = "webhook")
                    public TelegramBot telegramWebhookBot(DefaultBotOptions options) {
                        TelegramProductionBot bot = new TelegramProductionBot(options);
                        return bot;
                    }
                    @Bean
                    @ConditionalOnProperty(name = "telegram.bot.type", havingValue = "longpooling")
                    public TelegramBot telegramLongPollingBot(DefaultBotOptions options) {
                        return new TelegramDevBot(options);
                    }
                    @Bean
                    public Sender sender() {
                        return new MailSender();
                    }
                }
                """);
        files.put("TelegramProductionBot.java", "public class TelegramProductionBot extends TelegramWebhookBot {\n}\n");
        files.put("TelegramDevBot.java", "public class TelegramDevBot extends TelegramLongPollingBot {\n}\n");
        files.put("MailSender.java", "public class MailSender implements Sender {\n}\n");
        files.put("TelegramWebhookController.java", """
                @RestController
                @RequiredArgsConstructor
                public class TelegramWebhookController {
                    private final TelegramProductionBot bot;
                }
                """);
        files.put("TelegramClient.java", """
                @Service
                public class TelegramClient {
                    private TelegramBot bot;
                    private final Sender sender;
                    // отложенное внедрение получает бин позже, когда он уже создан
                    @Autowired
                    public void setBot(@Lazy TelegramDevBot bot) {
                        this.bot = bot;
                    }
                }
                """);
        List<Violation> violations = RuleTests.checkProject(new BeanDeclaredWiderThanInjectedRule(), files);
        assertThat(violations).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("TelegramBotConfig.java:3");
        assertThat(violations.get(0).message())
                .contains("'telegramWebhookBot'", "типом TelegramBot", "TelegramProductionBot",
                        "TelegramWebhookController");
    }

    @Test
    void beanInjectedIntoConsumerCreatedByBeanMethodIsNotJudged() {
        // Получателя создает @Bean-метод, объявленный ниже: к этому времени нужный бин уже есть
        Map<String, String> files = new LinkedHashMap<>();
        files.put("OssAutoConfiguration.java", """
                public class OssAutoConfiguration {
                    @Bean
                    @Primary
                    public FileTemplate ossTemplate() {
                        return new OssTemplate(properties);
                    }
                    @Bean
                    public OssEndpoint ossEndpoint(OssTemplate template) {
                        return new OssEndpoint(template);
                    }
                }
                """);
        files.put("OssTemplate.java", "public class OssTemplate implements FileTemplate {\n}\n");
        files.put("OssEndpoint.java", """
                @RestController
                @AllArgsConstructor
                public class OssEndpoint {
                    private final OssTemplate template;
                }
                """);
        assertThat(RuleTests.checkProject(new BeanDeclaredWiderThanInjectedRule(), files)).isEmpty();
    }

    @Test
    void beanDeclaredByItsOwnClassOrComponentIsFine() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("BotConfig.java", """
                @Configuration
                public class BotConfig {
                    @Bean
                    public TelegramProductionBot telegramWebhookBot() {
                        return new TelegramProductionBot();
                    }
                    @Bean
                    public Sender sender() {
                        return new MailSender();
                    }
                }
                """);
        files.put("TelegramProductionBot.java", "public class TelegramProductionBot {\n}\n");
        files.put("MailSender.java", "@Component\npublic class MailSender implements Sender {\n}\n");
        files.put("Consumer.java", """
                @Service
                @RequiredArgsConstructor
                public class Consumer {
                    private final TelegramProductionBot bot;
                    private final MailSender sender;
                }
                """);
        assertThat(RuleTests.checkProject(new BeanDeclaredWiderThanInjectedRule(), files)).isEmpty();
    }

    private static final String BOT_CONFIG = """
            @Configuration
            public class MaxBotConfig {
                @Bean
                @ConditionalOnProperty(name = "max.bot.type", havingValue = "longpooling")
                public MaxBot maxLongPollingBot() {
                    return new MaxDevBot();
                }
                @Bean
                @ConditionalOnProperty(name = "max.bot.type", havingValue = "dummy")
                public MaxBot maxDummyBot() {
                    return new MaxDummyBot();
                }
                @Bean
                @ConditionalOnProperty(name = "metrics.enabled", havingValue = "true")
                public Metrics metrics() {
                    return new Metrics();
                }
                @Bean
                @ConditionalOnProperty(prefix = "cache", name = "type", havingValue = "redis")
                public Cache redisCache() {
                    return new RedisCache();
                }
                @Bean
                @ConditionalOnProperty(prefix = "cache", name = "type")
                public CacheMetrics cacheMetrics() {
                    return new CacheMetrics();
                }
            }
            """;

    @Test
    void switchValueWithoutBeanIsFound(@TempDir Path projects) {
        List<Violation> violations = new ProjectFixture(projects.resolve("webhook"))
                .source("MaxBotConfig.java", BOT_CONFIG)
                .write("src/main/resources/application.properties", """
                        server.port=8106
                        max.bot.type=webhook
                        metrics.enabled=yes
                        cache.type=caffeine
                        """)
                .check(new PropertyValueWithoutConditionRule());
        assertThat(violations).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("application.properties:2");
        assertThat(violations.get(0).message())
                .contains("max.bot.type=webhook", "dummy, longpooling", "MaxBotConfig.maxDummyBot");
    }

    @Test
    void switchValueWithMeaningOfItsOwnIsNotJudged(@TempDir Path projects) {
        String properties = "max.bot.type=webhook\n";
        // Значение читает и сам код: что он делает с "лишним" значением, по условиям не видно
        assertThat(new ProjectFixture(projects.resolve("read"))
                .source("MaxBotConfig.java", BOT_CONFIG)
                .source("Launcher.java", """
                        @Component
                        public class Launcher {
                            public void start(Environment environment) {
                                String type = environment.getProperty("max.bot.type", "memory");
                            }
                        }
                        """)
                .write("src/main/resources/application.properties", properties)
                .check(new PropertyValueWithoutConditionRule())).isEmpty();
        assertThat(new ProjectFixture(projects.resolve("value"))
                .source("MaxBotConfig.java", BOT_CONFIG)
                .source("Sender.java", """
                        @Service
                        public class Sender {
                            @Value("${max.bot.type}")
                            private String botType;
                        }
                        """)
                .write("src/main/resources/application.properties", properties)
                .check(new PropertyValueWithoutConditionRule())).isEmpty();
        assertThat(new ProjectFixture(projects.resolve("bound"))
                .source("MaxBotConfig.java", BOT_CONFIG)
                .source("MaxBotProperties.java", """
                        @ConfigurationProperties(prefix = "max.bot")
                        public class MaxBotProperties {
                            private String type;
                        }
                        """)
                .write("src/main/resources/application.properties", properties)
                .check(new PropertyValueWithoutConditionRule())).isEmpty();
        // Для остальных значений есть запасной бин того же типа
        assertThat(new ProjectFixture(projects.resolve("fallback"))
                .source("MaxBotConfig.java", BOT_CONFIG)
                .source("DefaultBotConfig.java", """
                        @Configuration
                        public class DefaultBotConfig {
                            @Bean
                            @ConditionalOnMissingBean(MaxBot.class)
                            public MaxBot maxWebhookBot() {
                                return new MaxWebhookBot();
                            }
                        }
                        """)
                .write("src/main/resources/application.properties", properties)
                .check(new PropertyValueWithoutConditionRule())).isEmpty();
    }

    @Test
    void knownOffOrPlaceholderSwitchValueIsFine(@TempDir Path projects) {
        for (String value : List.of("longpooling", "DUMMY", "", "none", "${MAX_BOT_TYPE}", "${MAX_BOT_TYPE:dummy}")) {
            assertThat(new ProjectFixture(projects.resolve("ok" + Math.abs(value.hashCode())))
                    .source("MaxBotConfig.java", BOT_CONFIG)
                    .write("src/main/resources/application.properties", "max.bot.type=" + value + "\n")
                    .check(new PropertyValueWithoutConditionRule())).as(value).isEmpty();
        }
        // Значение по умолчанию у переменной окружения - такое же значение, как записанное прямо
        assertThat(new ProjectFixture(projects.resolve("default"))
                .source("MaxBotConfig.java", BOT_CONFIG)
                .write("src/main/resources/application.properties", "max.bot.type=${MAX_BOT_TYPE:webhook}\n")
                .lines(new PropertyValueWithoutConditionRule())).containsExactly("application.properties:1");
        // Настройки разработчика о рабочей среде ничего не говорят
        assertThat(new ProjectFixture(projects.resolve("local"))
                .source("MaxBotConfig.java", BOT_CONFIG)
                .write("src/main/resources/application-local.properties", "max.bot.type=webhook\n")
                .check(new PropertyValueWithoutConditionRule())).isEmpty();
    }

    @Test
    void settingThatRefersToIgnoredFileIsFound(@TempDir Path projects) {
        List<Violation> violations = new ProjectFixture(projects.resolve("ignored"))
                .write(".gitignore", "target/\n/src/main/resources/logging/logback-spring-local.xml\n*.pfx\n")
                .write("src/main/resources/logging/logback-spring-local.xml", "<configuration/>\n")
                .write("src/main/resources/logging/logback-spring.xml", "<configuration/>\n")
                .write("src/main/resources/ssl/keystore.pfx", "binary\n")
                .write("src/main/resources/application.properties", """
                        logging.config=classpath:logging/logback-spring-local.xml
                        server.ssl.key-store=classpath:ssl/keystore.pfx
                        app.banner=classpath:logging/logback-spring.xml
                        app.extra=optional:classpath:logging/logback-spring-local.xml
                        """)
                .check(new ConfigRefersToUntrackedFileRule());
        assertThat(violations).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("application.properties:1");
        assertThat(violations.get(0).message()).contains("logging.config", ".gitignore", "чистой копии");
        assertThat(violations.get(0).confidenceOrDefault()).isEqualTo(Confidence.PROBABLE);
    }

    @Test
    void settingThatRefersToMissingLoggingConfigIsFound(@TempDir Path projects) {
        assertThat(new ProjectFixture(projects.resolve("missing"))
                .write(".gitignore", "target/\n")
                .write("src/main/resources/logging/logback-spring.xml", "<configuration/>\n")
                .write("src/main/resources/application.properties", """
                        logging.config=classpath:logging/logback-prod.xml
                        app.template=classpath:templates/mail.html
                        spring.liquibase.change-log=classpath:db/changelog/master.xml
                        """)
                .lines(new ConfigRefersToUntrackedFileRule())).containsExactly("application.properties:1");
    }

    @Test
    void fileThatMayComeFromElsewhereIsNotCalledMissing(@TempDir Path projects) {
        ConfigRefersToUntrackedFileRule rule = new ConfigRefersToUntrackedFileRule();
        // Настройки лежат рядом с собранным приложением, а не в его ресурсах
        assertThat(new ProjectFixture(projects.resolve("deploy"))
                .write(".gitignore", "target/\n")
                .write("deploy/app/logging/readme.txt", "logs\n")
                .write("deploy/app/application.yml", "logging:\n  config: classpath:logging/logback-prod.xml\n")
                .check(rule)).isEmpty();
        // Файл из корня classpath может принести и библиотека
        assertThat(new ProjectFixture(projects.resolve("root"))
                .write(".gitignore", "target/\n")
                .write("src/main/resources/application.properties", "logging.config=classpath:logback-company.xml\n")
                .check(rule)).isEmpty();
        // Файл лежит в ресурсах соседнего модуля
        assertThat(new ProjectFixture(projects.resolve("modules"))
                .write(".gitignore", "target/\n")
                .write("common/pom.xml", "<project/>\n")
                .write("common/src/main/resources/logging/logback-prod.xml", "<configuration/>\n")
                .write("app/pom.xml", "<project/>\n")
                .write("app/src/main/resources/logging/other.xml", "<configuration/>\n")
                .write("app/src/main/resources/application.properties",
                        "logging.config=classpath:logging/logback-prod.xml\n")
                .check(rule)).isEmpty();
    }

    private static String pom(int java) {
        return """
                <project>
                    <properties>
                        <java.version>%d</java.version>
                    </properties>
                </project>
                """.formatted(java);
    }

    @Test
    void imageWithOlderJavaThanBuildIsFound(@TempDir Path projects) {
        List<Violation> violations = new ProjectFixture(projects.resolve("old"))
                .write("pom.xml", pom(21))
                .write("Dockerfile", """
                        FROM openjdk:11
                        COPY target/*.jar /app/app.jar
                        ENTRYPOINT ["java", "-jar", "/app/app.jar"]
                        """)
                .check(new DockerJavaOlderThanBuildRule());
        assertThat(violations).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("Dockerfile:1");
        assertThat(violations.get(0).message()).contains("Java 11", "Java 21", "UnsupportedClassVersionError");
        assertThat(violations.get(0).errorLevel()).isEqualTo(ErrorLevel.CRITICAL);

        // Собирают на новой Java, запускают на старой: значение имеет последний этап
        assertThat(new ProjectFixture(projects.resolve("stages"))
                .write("pom.xml", pom(21))
                .write("Dockerfile", """
                        FROM maven:3.9-eclipse-temurin-21 AS build
                        RUN mvn -q package
                        FROM eclipse-temurin:17.0.9_9-jre-alpine
                        COPY --from=build /src/target/app.jar /app.jar
                        """)
                .lines(new DockerJavaOlderThanBuildRule())).containsExactly("Dockerfile:3");
    }

    @Test
    void imageWithSameNewerOrUnknownJavaIsFine(@TempDir Path projects) {
        for (String image : List.of("eclipse-temurin:21-jre", "amazoncorretto:25", "gcr.io/distroless/java21-debian12",
                "openjdk:${JAVA_VERSION}", "alpine:3.19", "registry.local/base/app-runtime:11", "openjdk")) {
            assertThat(new ProjectFixture(projects.resolve("ok" + Math.abs(image.hashCode())))
                    .write("pom.xml", pom(21))
                    .write("Dockerfile", "FROM " + image + "\nCOPY target/app.jar /app.jar\n")
                    .check(new DockerJavaOlderThanBuildRule())).as(image).isEmpty();
        }
        // Версия Java в сборке не названа: сравнивать не с чем
        assertThat(new ProjectFixture(projects.resolve("unknown"))
                .write("pom.xml", "<project/>\n")
                .write("Dockerfile", "FROM openjdk:8\n")
                .check(new DockerJavaOlderThanBuildRule())).isEmpty();
    }
}
