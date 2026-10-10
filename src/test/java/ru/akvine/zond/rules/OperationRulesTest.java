package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.rules.concurrency.SchedulerSingleThreadRule;
import ru.akvine.zond.rules.resources.MdcWithoutCleanupRule;
import ru.akvine.zond.rules.security.UnboundedPageSizeRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:383 - jr:385: планировщик в один поток, MDC без очистки и размер страницы без верхней границы
 */
class OperationRulesTest {
    private static final String JOBS = """
            @Component
            class Jobs {
                @Scheduled(fixedRate = 1000)
                void first() {}
                @Scheduled(cron = "0 0 * * * *")
                void second() {}
                @Async
                @Scheduled(fixedDelay = 5000)
                void third() {}
            }
            """;

    @TempDir
    Path dir;

    @Test
    void severalScheduledTasksNeedMoreThanOneThread() {
        SchedulerSingleThreadRule rule = new SchedulerSingleThreadRule();
        ProjectFixture plain = new ProjectFixture(dir.resolve("plain")).source("Jobs.java", JOBS);
        // Задача с @Async выполняется в другом пуле и очередь планировщика не занимает
        assertThat(plain.lines(rule)).containsExactly("Jobs.java:3");
        assertThat(plain.check(rule).get(0).message()).contains("2 задач").contains("spring.task.scheduling.pool.size");

        ProjectFixture withPool = new ProjectFixture(dir.resolve("pool"))
                .source("Jobs.java", JOBS)
                .write("src/main/resources/application.properties", "spring.task.scheduling.pool.size=4\n");
        assertThat(withPool.lines(rule)).isEmpty();

        // Пул из одного потока, заданный явно, ничего не меняет
        ProjectFixture singlePool = new ProjectFixture(dir.resolve("single"))
                .source("Jobs.java", JOBS)
                .write("src/main/resources/application.properties", "spring.task.scheduling.pool.size=1\n");
        assertThat(singlePool.lines(rule)).containsExactly("Jobs.java:3");

        ProjectFixture ownScheduler = new ProjectFixture(dir.resolve("own"))
                .source("Jobs.java", JOBS)
                .source("SchedulerConfig.java", """
                        @Configuration
                        class SchedulerConfig {
                            @Bean
                            ThreadPoolTaskScheduler taskScheduler() {
                                return new ThreadPoolTaskScheduler();
                            }
                        }
                        """);
        assertThat(ownScheduler.lines(rule)).isEmpty();

        // Задачи разных модулей работают в разных приложениях: каждая в своем планировщике одна
        String job = "@Component class %s { @Scheduled(fixedRate = 1000) void run() {} }";
        ProjectFixture modules = new ProjectFixture(dir.resolve("modules"))
                .source("billing/src/main/java/BillingJob.java", job.formatted("BillingJob"))
                .source("mail/src/main/java/MailJob.java", job.formatted("MailJob"));
        assertThat(modules.lines(rule)).isEmpty();
        ProjectFixture sameModule = new ProjectFixture(dir.resolve("module"))
                .source("billing/src/main/java/BillingJob.java", job.formatted("BillingJob"))
                .source("billing/src/main/java/jobs/CleanupJob.java", job.formatted("CleanupJob"));
        assertThat(sameModule.lines(rule)).containsExactly("BillingJob.java:1");

        // Одной задаче ждать некого
        ProjectFixture single = new ProjectFixture(dir.resolve("one")).source("Job.java", """
                @Component
                class Job {
                    @Scheduled(fixedRate = 1000)
                    void only() {}
                }
                """);
        assertThat(single.lines(rule)).isEmpty();
    }

    @Test
    void mdcMustBeCleanedInFinally() {
        List<Integer> lines = RuleTests.lines(new MdcWithoutCleanupRule(), """
                class Handlers {
                    void leaky(String id) {
                        MDC.put("requestId", id);
                        process(id);
                    }
                    void notInFinally(String id) {
                        MDC.put("requestId", id);
                        process(id);
                        MDC.remove("requestId");
                    }
                    void correct(String id) {
                        MDC.put("requestId", id);
                        try {
                            process(id);
                        } finally {
                            MDC.remove("requestId");
                        }
                    }
                    void closeable(String id) {
                        try (MDC.MDCCloseable ignored = MDC.putCloseable("requestId", id)) {
                            process(id);
                        }
                    }
                    void notMdc(String id) {
                        context.put("requestId", id);
                    }
                }
                class TraceFilter {
                    void before(String id) {
                        MDC.put("traceId", id);
                    }
                    void after() {
                        MDC.clear();
                    }
                }
                """);

        // Фильтр кладет значение в одном методе, а убирает в другом - это не утечка
        assertThat(lines).containsExactly(3, 9);
    }

    @Test
    void pageSizeFromRequestNeedsUpperBound() {
        ProjectFixture project = new ProjectFixture(dir)
                .source("ItemController.java", """
                        @RestController
                        class ItemController {
                            private ItemService itemService;
                            @GetMapping("/items")
                            Page<Item> items(@RequestParam int page, @RequestParam int size) {
                                return repository.findAll(PageRequest.of(page, size));
                            }
                            @GetMapping("/limited")
                            Page<Item> limited(@RequestParam int page, @RequestParam @Max(100) int size) {
                                return repository.findAll(PageRequest.of(page, size));
                            }
                            @GetMapping("/clamped")
                            Page<Item> clamped(@RequestParam int page, @RequestParam int size) {
                                return repository.findAll(PageRequest.of(page, Math.min(size, 100)));
                            }
                            @GetMapping("/checked")
                            Page<Item> checked(@RequestParam int page, @RequestParam int size) {
                                if (size > 100) {
                                    throw new IllegalArgumentException();
                                }
                                return repository.findAll(PageRequest.of(page, size));
                            }
                            @GetMapping("/fixed")
                            Page<Item> fixed(@RequestParam int page) {
                                return repository.findAll(PageRequest.of(page, 20));
                            }
                            @GetMapping("/pageable")
                            Page<Item> pageable(Pageable pageable) {
                                return repository.findAll(pageable);
                            }
                            @GetMapping("/via-service")
                            Page<Item> viaService(@RequestParam int page, @RequestParam int limit) {
                                return itemService.find(page, limit);
                            }
                            @PostMapping("/search")
                            Page<Item> search(@RequestBody SearchRequest request) {
                                return repository.findAll(PageRequest.of(request.getPage(), request.getSize()));
                            }
                            @PostMapping("/safe-search")
                            Page<Item> safeSearch(@RequestBody SafeRequest request) {
                                return repository.findAll(PageRequest.of(request.getPage(), request.getSize()));
                            }
                        }
                        """)
                .source("ItemService.java", """
                        class ItemService {
                            Page<Item> find(int page, int size) {
                                return repository.findAll(PageRequest.of(page, size));
                            }
                        }
                        """)
                .source("SearchRequest.java", "class SearchRequest { private int page; private int size; }")
                .source("SafeRequest.java", "class SafeRequest { private int page; @Max(200) private int size; }");

        // Параметры запроса объявлены в интерфейсе: оттуда же берется и ограничение
        ProjectFixture byContract = new ProjectFixture(dir)
                .source("OrderApi.java", """
                        interface OrderApi {
                            @GetMapping("/orders")
                            Page<Order> orders(@RequestParam int page, @RequestParam int size);
                            @GetMapping("/limited-orders")
                            Page<Order> limited(@RequestParam int page, @RequestParam @Max(50) int size);
                        }
                        """)
                .source("OrderController.java", """
                        @RestController
                        class OrderController implements OrderApi {
                            public Page<Order> orders(int page, int size) {
                                return repository.findAll(PageRequest.of(page, size));
                            }
                            public Page<Order> limited(int page, int size) {
                                return repository.findAll(PageRequest.of(page, size));
                            }
                        }
                        """);
        assertThat(byContract.lines(new UnboundedPageSizeRule())).containsExactly("OrderController.java:4");

        // @Max, Math.min и проверка в if ограничивают размер; Pageable в параметре Spring ограничивает сам
        assertThat(project.lines(new UnboundedPageSizeRule())).containsExactly(
                "ItemController.java:33", "ItemController.java:37", "ItemController.java:6");
    }
}
