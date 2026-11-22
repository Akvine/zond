package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.loaders.FileSystemSourceLoader;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.concurrency.InheritableThreadLocalWithPoolRule;
import ru.akvine.zond.rules.resources.ExecutorTasksLostOnShutdownRule;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jr:563, jr:564: пул потоков и то, что с ним связано не на все время, - задачи, которые пропадают при
 * остановке приложения, и значение потока, которое пул наследует один раз
 */
class PoolLifecycleRulesTest {
    private static final String POOLS = """
            import javax.annotation.PostConstruct;
            @Configuration
            public class Pools {
                @Bean
                public AsyncTaskExecutor smsExecutor(SmsProperties properties) {
                    ThreadPoolTaskExecutor executor = new MonitoredExecutor();
                    executor.setCorePoolSize(properties.getCore());
                    executor.setQueueCapacity(properties.getQueue());
                    return executor;
                }
                @Bean
                public TaskScheduler scheduler() {
                    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
                    scheduler.setPoolSize(4);
                    return scheduler;
                }
                @Bean
                public AsyncTaskExecutor mailExecutor() {
                    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
                    executor.setWaitForTasksToCompleteOnShutdown(true);
                    executor.setAwaitTerminationSeconds(30);
                    return executor;
                }
            }
            """;
    private static final String POM = """
            <project>
              <parent>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-parent</artifactId>
                <version>%s</version>
              </parent>
            </project>
            """;

    @Test
    void poolThatDropsTasksOnShutdownIsFound(@TempDir Path projects) {
        ExecutorTasksLostOnShutdownRule rule = new ExecutorTasksLostOnShutdownRule();
        // Версии Spring Boot в сборке нет (свой родительский pom): о старом Spring говорят пакеты javax
        List<Violation> violations = new ProjectFixture(projects.resolve("legacy"))
                .source("Pools.java", POOLS).check(rule);
        assertThat(violations).extracting(Violation::line).containsExactly(6, 13);
        assertThat(violations.get(0).message()).contains(
                "'executor' (ThreadPoolTaskExecutor)", "shutdownNow()", "setWaitForTasksToCompleteOnShutdown(true)");
        assertThat(new ProjectFixture(projects.resolve("boot2"))
                .source("Pools.java", POOLS.replace("javax.annotation", "jakarta.annotation"))
                .write("pom.xml", POM.formatted("3.1.4")).check(rule))
                .extracting(Violation::line).containsExactly(6, 13);
    }

    @Test
    void poolOfSpringWithGracefulStopIsFine(@TempDir Path projects) {
        ExecutorTasksLostOnShutdownRule rule = new ExecutorTasksLostOnShutdownRule();
        // С Spring Boot 3.2 пул при закрытии контекста сам дает принятым задачам завершиться
        assertThat(new ProjectFixture(projects.resolve("modern"))
                .source("Pools.java", POOLS.replace("javax.annotation", "jakarta.annotation"))
                .write("pom.xml", POM.formatted("3.3.1")).check(rule)).isEmpty();
        // Версия неизвестна, пакетов javax нет: судить не по чему
        assertThat(new ProjectFixture(projects.resolve("unknown"))
                .source("Pools.java", POOLS.replace("javax.annotation", "jakarta.annotation")).check(rule)).isEmpty();
    }

    private static final String HOLDER = """
            public class TenantHolder {
                private static final ThreadLocal<String> TENANT = new InheritableThreadLocal<>();
                private static final ThreadLocal<String> TRACE = new ThreadLocal<>();
                public static void set(String tenant) {
                    TENANT.set(tenant);
                }
            }
            """;

    @Test
    void inheritableThreadLocalNextToPoolIsFound() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("TenantHolder.java", HOLDER);
        files.put("Notifier.java", """
                @Service
                public class Notifier {
                    public void send(Message message) {
                        CompletableFuture.runAsync(() -> deliver(message));
                    }
                }
                """);
        List<Violation> violations = RuleTests.checkProject(new InheritableThreadLocalWithPoolRule(), files);
        assertThat(violations).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("TenantHolder.java:2");
        assertThat(violations.get(0).message())
                .contains("CompletableFuture.runAsync", "один раз", "TaskDecorator");
    }

    @Test
    void inheritableThreadLocalWithoutPoolsIsFine() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("TenantHolder.java", HOLDER);
        // Планировщик создает свои потоки при запуске, а не из потока запроса
        files.put("Cleaner.java", """
                @Component
                public class Cleaner {
                    @Scheduled(fixedDelay = 60000)
                    public void clean() {
                    }
                }
                """);
        assertThat(RuleTests.checkProject(new InheritableThreadLocalWithPoolRule(), files)).isEmpty();
    }

    private static final String APPLICATION = """
            package %s;
            @SpringBootApplication
            public class Application {
                public static void main(String[] args) {
                    SpringApplication.run(Application.class, args);
                }
            }
            """;
    private static final String SENDER = """
            package %s;
            @Service
            public class Sender {
                private final ExecutorService pool = Executors.newFixedThreadPool(4);
            }
            """;

    @Test
    void poolOfNeighbourApplicationDoesNotCount(@TempDir Path dir) {
        // Два приложения в одном хранилище: пул сервиса billing задач сервиса shop не выполняет
        ProjectFixture project = new ProjectFixture(dir)
                .write("shop/pom.xml", "<project/>")
                .write("shop/src/main/java/shop/Application.java", APPLICATION.formatted("shop"))
                .write("shop/src/main/java/shop/TenantHolder.java", "package shop;\n" + HOLDER)
                .write("billing/pom.xml", "<project/>")
                .write("billing/src/main/java/billing/Application.java", APPLICATION.formatted("billing"))
                .write("billing/src/main/java/billing/Sender.java", SENDER.formatted("billing"));
        InheritableThreadLocalWithPoolRule rule = new InheritableThreadLocalWithPoolRule();
        assertThat(rule.checkProject(load(dir))).isEmpty();

        // Пул общей библиотеки попадает в оба приложения
        project.write("common/pom.xml", "<project/>")
                .write("common/src/main/java/common/Sender.java", SENDER.formatted("common"));
        assertThat(rule.checkProject(load(dir)))
                .extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("TenantHolder.java:3");
    }

    private static List<SourceFile> load(Path dir) {
        return new FileSystemSourceLoader().load(dir, path -> true, List.of()).sources();
    }
}
