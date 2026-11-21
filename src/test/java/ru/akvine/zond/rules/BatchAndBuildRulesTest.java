package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.logical.LibraryForAnotherBootGenerationRule;
import ru.akvine.zond.rules.logical.OnlyFirstElementProcessedRule;
import ru.akvine.zond.rules.security.UnsupportedSpringBootRule;

import java.nio.file.Path;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jr:550 - jr:552: набор, из которого обрабатывают только первый элемент; библиотека для другого поколения
 * Spring Boot; ветка Spring Boot без открытой поддержки
 */
class BatchAndBuildRulesTest {
    private static List<Violation> firstOnly(String bot) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Update.java", "public class Update {\n}\n");
        files.put("Message.java", "public class Message {\n}\n");
        files.put("Bot.java", bot);
        return RuleTests.checkProject(new OnlyFirstElementProcessedRule(), files);
    }

    @Test
    void batchOfWhichOnlyFirstElementIsProcessedIsFound() {
        List<Violation> violations = firstOnly("""
                public class Bot {
                    public Response onUpdateReceived(Update[] updates) {
                        if (updates.length != 0) {
                            Update update = updates[0];
                            Message[] messages = client.getMessages(update.getChatId());
                            if (messages.length != 0) {
                                update.setMessage(messages[0]);
                                client.send(update);
                            }
                        }
                        return Response.empty();
                    }
                    public void saveAll(List<Message> messages) {
                        if (messages.isEmpty()) {
                            return;
                        }
                        log.info("saving {}", messages);
                        repository.save(messages.get(0));
                    }
                    public void first(Collection<Update> updates) {
                    }
                    // назначение метода по имени неясно: только подозрение
                    public void route(List<Update> updates) {
                        router.accept(updates.iterator().next());
                    }
                }
                """);
        assertThat(violations).extracting(Violation::line).containsExactly(4, 18, 24);
        assertThat(violations.get(0).message()).contains("'onUpdateReceived'", "'updates' (Update[])", "'updates[0]'");
        assertThat(violations).extracting(Violation::confidenceOrDefault)
                .containsExactly(Confidence.PROBABLE, Confidence.PROBABLE, Confidence.SUSPICION);
    }

    @Test
    void batchThatIsIteratedPassedOnOrPickedFromByDesignIsFine() {
        assertThat(firstOnly("""
                public class Bot {
                    public void handleAll(List<Update> updates) {
                        Update first = updates.get(0);
                        audit.started(first);
                        for (Update update : updates) {
                            process(update);
                        }
                    }
                    public void handlePage(Update[] updates) {
                        audit.started(updates[0]);
                        processor.process(updates);
                    }
                    public void handlePair(Update[] updates) {
                        merge(updates[0], updates[1]);
                    }
                    public void handleStream(List<Update> updates) {
                        audit.started(updates.get(0));
                        updates.stream().skip(1).forEach(this::process);
                    }
                    // первый элемент выбирают по назначению метода
                    public Update pickFirst(List<Update> updates) {
                        audit.picked(updates.get(0));
                        return updates.get(0);
                    }
                    public Response toResponse(List<Update> updates) {
                        Response response = new Response();
                        response.setChat(updates.get(0).getChatId());
                        return response;
                    }
                    // чужие и позиционные наборы
                    public void handleArguments(String[] lines, Update[] args) {
                        printer.print(lines[0]);
                        printer.print(args[0]);
                    }
                    // ячейка для результата
                    public void handleResult(Update[] holders) {
                        holders[0] = client.next();
                        audit.done();
                    }
                    public void handleMany(Update... updates) {
                        process(updates[0]);
                    }
                    // только вычисляет
                    public long sizeOf(List<Update> updates) {
                        return updates.get(0).size();
                    }
                }
                """)).isEmpty();
    }

    @Test
    void batchAlwaysBuiltFromOneElementIsFine() {
        assertThat(firstOnly("""
                public class Bot {
                    public void onUpdate(Update update) {
                        handle(List.of(update));
                        handle(Collections.singletonList(update));
                    }
                    private void handle(List<Update> updates) {
                        process(updates.get(0));
                    }
                }
                """)).isEmpty();
    }

    private static String pom(String boot, String dependencies) {
        return """
                <project>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>%s</version>
                    </parent>
                    <dependencies>
                %s
                    </dependencies>
                </project>
                """.formatted(boot, dependencies.indent(8).stripTrailing());
    }

    private static String dependency(String group, String artifact, String version) {
        return """
                <dependency>
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>
                </dependency>
                """.formatted(group, artifact, version);
    }

    private static final String OLD_LIBRARIES = dependency("org.springdoc", "springdoc-openapi-ui", "1.7.0")
            + dependency("io.github.resilience4j", "resilience4j-spring-boot2", "1.7.0")
            + dependency("io.springfox", "springfox-boot-starter", "3.0.0")
            + dependency("org.mybatis.spring.boot", "mybatis-spring-boot-starter", "2.3.2")
            + dependency("org.springframework.boot", "spring-boot-starter-web", "3.3.1");
    private static final String NEW_LIBRARIES =
            dependency("org.springdoc", "springdoc-openapi-starter-webmvc-ui", "2.6.0")
                    + dependency("io.github.resilience4j", "resilience4j-spring-boot3", "2.2.0")
                    + dependency("org.mybatis.spring.boot", "mybatis-spring-boot-starter", "3.0.4");

    @Test
    void libraryForAnotherBootGenerationIsFound(@TempDir Path projects) {
        LibraryForAnotherBootGenerationRule rule = new LibraryForAnotherBootGenerationRule();
        List<Violation> violations = new ProjectFixture(projects.resolve("boot3"))
                .write("pom.xml", pom("3.3.1", OLD_LIBRARIES))
                .check(rule);
        assertThat(violations).extracting(Violation::line).containsExactly(8, 13, 18, 23);
        assertThat(violations.get(0).message()).contains("org.springdoc:springdoc-openapi-ui:1.7.0",
                "Spring Boot 3", "springdoc-openapi-starter-webmvc-ui");
        assertThat(violations.get(1).message()).contains("resilience4j-spring-boot3", "spring-boot-starter-aop");
        assertThat(violations.get(2).message()).contains("Springfox");
        assertThat(violations.get(3).message()).contains("версии 2.x", "версии 3.x");

        // Стартеры третьего поколения в приложении на Spring Boot 2
        assertThat(new ProjectFixture(projects.resolve("boot2"))
                .write("pom.xml", pom("2.7.18", NEW_LIBRARIES))
                .check(rule)).extracting(Violation::line).containsExactly(8, 13, 18);
    }

    @Test
    void libraryOfMatchingGenerationIsFine(@TempDir Path projects) {
        LibraryForAnotherBootGenerationRule rule = new LibraryForAnotherBootGenerationRule();
        assertThat(new ProjectFixture(projects.resolve("boot3"))
                .write("pom.xml", pom("3.3.1", NEW_LIBRARIES))
                .check(rule)).isEmpty();
        assertThat(new ProjectFixture(projects.resolve("boot2"))
                .write("pom.xml", pom("2.7.18", OLD_LIBRARIES))
                .check(rule)).isEmpty();
        // Версия Spring Boot в сборке не названа либо у приложений хранилища она разная
        assertThat(new ProjectFixture(projects.resolve("unknown"))
                .write("pom.xml", "<project>\n<dependencies>\n" + OLD_LIBRARIES + "</dependencies>\n</project>\n")
                .check(rule)).isEmpty();
        assertThat(new ProjectFixture(projects.resolve("mixed"))
                .write("old/pom.xml", pom("2.7.18", OLD_LIBRARIES))
                .write("new/pom.xml", pom("3.3.1", OLD_LIBRARIES))
                .check(rule)).isEmpty();
    }

    private static UnsupportedSpringBootRule ruleAt(int year, int month) {
        return new UnsupportedSpringBootRule() {
            @Override
            protected YearMonth today() {
                return YearMonth.of(year, month);
            }
        };
    }

    @Test
    void springBootBranchWithoutSupportIsFound(@TempDir Path projects) {
        List<Violation> violations = new ProjectFixture(projects.resolve("old"))
                .write("pom.xml", pom("3.3.1", ""))
                .check(ruleAt(2026, 11));
        assertThat(violations).extracting(Violation::line).containsExactly(5);
        assertThat(violations.get(0).message()).contains("Spring Boot 3.3.1", "ветки 3.3", "июня 2025 года",
                "4.0 (до конца декабря 2026 года), 4.1 (до конца июля 2027 года)");

        assertThat(new ProjectFixture(projects.resolve("ancient"))
                .write("build.gradle", "plugins {\n    id 'org.springframework.boot' version '2.3.4.RELEASE'\n}\n")
                .check(ruleAt(2026, 11)))
                .extracting(Violation::message)
                .allMatch(message -> message.contains("ветки 2.3") && message.contains("раньше, чем у ветки 2.7"))
                .hasSize(1);
    }

    @Test
    void supportedOrUnknownSpringBootBranchIsFine(@TempDir Path projects) {
        // В последний месяц поддержки сообщать рано
        assertThat(new ProjectFixture(projects.resolve("last-month"))
                .write("pom.xml", pom("3.3.1", ""))
                .check(ruleAt(2025, 6))).isEmpty();
        assertThat(new ProjectFixture(projects.resolve("current"))
                .write("pom.xml", pom("4.1.2", ""))
                .check(ruleAt(2026, 11))).isEmpty();
        // Ветка новее правила: о ее сроках оно не знает
        assertThat(new ProjectFixture(projects.resolve("future"))
                .write("pom.xml", pom("4.2.0", ""))
                .check(ruleAt(2030, 1))).isEmpty();
        assertThat(new ProjectFixture(projects.resolve("none"))
                .write("pom.xml", "<project/>\n")
                .check(ruleAt(2026, 11))).isEmpty();
    }
}
