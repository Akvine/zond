package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.ErrorMaskingSettingRule;
import ru.akvine.zond.rules.logical.CompareToEqualsOneRule;
import ru.akvine.zond.rules.logical.RabbitAcknowledgeModeNoneRule;
import ru.akvine.zond.rules.logical.RandomTruncatedToZeroRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:393 - jr:396: случайное число, обращенное в ноль, сравнение compareTo с единицей, настройки,
 * которые прячут ошибки, и режим подтверждения NONE у RabbitMQ
 */
class PitfallAndSettingRulesTest {

    @TempDir
    Path dir;

    @Test
    void randomCastBeforeMultiplicationIsAlwaysZero() {
        RandomTruncatedToZeroRule rule = new RandomTruncatedToZeroRule();
        List<Violation> violations = RuleTests.check(rule, """
                class Dice {
                    int wrong(int n) { return (int) Math.random() * n; }
                    int right(int n) { return (int) (Math.random() * n); }
                    int alone() { return (int) Math.random(); }
                    long wide(Random random) { return (long) random.nextDouble() * 100; }
                    int bounded(Random random) { return (int) random.nextDouble(10.0); }
                    int ok(Random random) { return random.nextInt(6); }
                    double real() { return (double) Math.random() * 6; }
                }
                """);

        // Произведение в скобках приводится целиком; nextDouble с границей и приведение к double нуля не дают
        assertThat(violations).extracting(Violation::line).containsExactly(2, 4, 5);
        assertThat(violations.get(0).message()).contains("(int) (Math.random() * n)").contains("раньше, чем умножается");
        assertThat(rule.confidence()).isEqualTo(Confidence.CONFIRMED);
    }

    @Test
    void compareToResultIsSignNotOne() {
        List<Violation> violations = RuleTests.check(new CompareToEqualsOneRule(), """
                class Sorter {
                    boolean a(String x, String y) { return x.compareTo(y) == 1; }
                    boolean b(String x, String y) { return x.compareTo(y) > 0; }
                    boolean c(LocalDate x, LocalDate y) { return x.compareTo(y) == -1; }
                    boolean d(BigDecimal x, BigDecimal y) { return x.compareTo(y) == 1; }
                    boolean e(Comparator<Item> comparator, Item x, Item y) { return comparator.compare(x, y) != 1; }
                    boolean f(int x, int y) { return Integer.compare(x, y) == 1; }
                    boolean g(String x, String y) { return 1 == x.compareToIgnoreCase(y); }
                    boolean h(String x, String y) { return x.compareTo(y) == 0; }
                    boolean i(Integer x, Integer y) { return x.compareTo(y) == 1; }
                    boolean j(int count) { return count == 1; }
                }
                """);

        // BigDecimal и числовые обертки возвращают ровно -1, 0 или 1 - там сравнение с единицей работает
        assertThat(violations).extracting(Violation::line).containsExactly(2, 4, 6, 8);
        assertThat(violations.get(0).message()).contains("'> 0'");
        assertThat(violations.get(1).message()).contains("'< 0'");
        assertThat(violations.get(2).message()).contains("'<= 0'");
    }

    @Test
    void settingsThatHideErrorsAreReported() {
        ErrorMaskingSettingRule rule = new ErrorMaskingSettingRule();
        ProjectFixture project = new ProjectFixture(dir)
                .write("src/main/resources/application.properties", """
                        spring.jpa.properties.hibernate.enable_lazy_load_no_trans=true
                        spring.main.allow-circular-references=true
                        spring.main.allow-bean-definition-overriding=false
                        spring.jpa.open-in-view=false
                        """)
                .write("src/main/resources/application-prod.yml", """
                        spring:
                          main:
                            allow-bean-definition-overriding: true
                        """)
                .write("src/test/resources/application.properties", "spring.main.allow-bean-definition-overriding=true\n")
                .write("src/main/resources/application-test.properties", "spring.main.allow-circular-references=true\n");

        // В настройках тестов подмена бинов - обычный прием; выключенная настройка ничего не прячет
        List<String> found = project.context().configFiles().stream()
                .flatMap(file -> rule.checkConfig(file).stream())
                .map(violation -> violation.file().getFileName() + ":" + violation.line())
                .sorted()
                .toList();
        assertThat(found).containsExactly("application-prod.yml:3", "application.properties:1", "application.properties:2");

        ConfigFile main = project.context().configFiles().stream()
                .filter(file -> file.path().endsWith(Path.of("src/main/resources/application.properties")))
                .findFirst()
                .orElseThrow();
        assertThat(rule.checkConfig(main)).extracting(Violation::message)
                .anyMatch(message -> message.contains("enable_lazy_load_no_trans=true") && message.contains("LazyInitializationException"))
                .anyMatch(message -> message.contains("allow-circular-references=true") && message.contains("цикл"));
    }

    @Test
    void rabbitAcknowledgeModeNoneLosesMessages() {
        RabbitAcknowledgeModeNoneRule rule = new RabbitAcknowledgeModeNoneRule();
        ProjectFixture project = new ProjectFixture(dir.resolve("none"))
                .write("src/main/resources/application.yml", """
                        spring:
                          rabbitmq:
                            listener:
                              simple:
                                acknowledge-mode: none
                        """)
                .write("src/test/resources/application.properties", "spring.rabbitmq.listener.simple.acknowledge-mode=none\n")
                .source("RabbitConfig.java", """
                        @Configuration
                        class RabbitConfig {
                            @Bean
                            SimpleRabbitListenerContainerFactory fast(ConnectionFactory connectionFactory) {
                                SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
                                factory.setAcknowledgeMode(AcknowledgeMode.NONE);
                                return factory;
                            }
                            @Bean
                            SimpleRabbitListenerContainerFactory careful(ConnectionFactory connectionFactory) {
                                SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
                                factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
                                return factory;
                            }
                        }
                        """)
                .source("Listeners.java", """
                        class Listeners {
                            @RabbitListener(queues = "events", ackMode = "NONE")
                            void onEvent(String message) {}
                            @RabbitListener(queues = "orders", ackMode = "MANUAL")
                            void onOrder(String message) {}
                            @RabbitListener(queues = "plain")
                            void onPlain(String message) {}
                        }
                        """);

        // Настройки тестов не в счет; режимы AUTO и MANUAL сообщение не теряют
        assertThat(project.lines(rule)).containsExactly("Listeners.java:2", "RabbitConfig.java:6", "application.yml:5");
        assertThat(rule.confidence()).isEqualTo(Confidence.CONFIRMED);

        ProjectFixture auto = new ProjectFixture(dir.resolve("auto"))
                .write("src/main/resources/application.properties", "spring.rabbitmq.listener.simple.acknowledge-mode=auto\n");
        assertThat(auto.lines(rule)).isEmpty();
    }
}
