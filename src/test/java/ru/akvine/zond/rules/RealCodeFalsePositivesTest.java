package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.ExecutorWithoutMdcRule;
import ru.akvine.zond.rules.codesmell.LargeClassRule;
import ru.akvine.zond.rules.codesmell.UnusedLocalVariableRule;
import ru.akvine.zond.rules.concurrency.ScheduledWithoutLockRule;
import ru.akvine.zond.rules.datetime.StaticSimpleDateFormatRule;
import ru.akvine.zond.rules.logical.AuditingNotEnabledRule;
import ru.akvine.zond.rules.logical.ConstraintOnWrongTypeRule;
import ru.akvine.zond.rules.logical.UsuallyCheckedResultIgnoredRule;
import ru.akvine.zond.rules.performance.FileContentInEntityRule;
import ru.akvine.zond.rules.performance.RelationContainsInLoopRule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ложные срабатывания, найденные прогоном по 38 рабочим проектам: свои обертки и аннотации проекта, объекты
 * для передачи данных, сущности Jmix
 */
class RealCodeFalsePositivesTest {

    @Test
    void executorNamedAfterMdcPropagatesIt() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("TraceFilter.java", """
                class TraceFilter {
                    void doFilter(String traceId) {
                        MDC.put("traceId", traceId);
                    }
                }
                """);
        files.put("AsyncConfig.java", """
                @Configuration
                class AsyncConfig {
                    @Bean
                    Executor syncExecutor() {
                        return new MdcThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100));
                    }
                    @Bean
                    Executor reportExecutor() {
                        return new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100));
                    }
                }
                """);

        assertThat(RuleTests.checkProject(new ExecutorWithoutMdcRule(), files))
                .extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("AsyncConfig.java:9");
    }

    @Test
    void ownLockAnnotationGuardsScheduledMethod() {
        assertThat(RuleTests.lines(new ScheduledWithoutLockRule(), """
                @Component
                class CleanupJob {
                    private final OrderRepository orderRepository;
                    @SingleInstance
                    @Scheduled(cron = "0 0 * * * *")
                    public void cleanOrders() {
                        orderRepository.deleteAll(orderRepository.findExpired());
                    }
                    @Scheduled(cron = "0 30 * * * *")
                    public void cleanDrafts() {
                        orderRepository.deleteAll(orderRepository.findDrafts());
                    }
                }
                """)).containsExactly(9);
    }

    @Test
    void varargsOfPrimitivesIsArray() {
        assertThat(RuleTests.lines(new ConstraintOnWrongTypeRule(), """
                class Steps {
                    Steps skip(@Nullable Integer next, @NotNull boolean... flags) {
                        return this;
                    }
                    Steps enable(@NotNull boolean flag) {
                        return this;
                    }
                }
                """)).containsExactly(5);
    }

    @Test
    void synchronizedWrapperOfDateFormatIsSafe() {
        assertThat(RuleTests.lines(new StaticSimpleDateFormatRule(), """
                class Names {
                    private static final SimpleDateFormat SAFE = new SyncSimpleDateFormat("yyyy-MM-dd");
                    private static final SimpleDateFormat SHARED = new SimpleDateFormat("yyyy-MM-dd");
                }
                """)).containsExactly(3);
    }

    @Test
    void variableNamedIgnoredIsLeftOnPurpose() {
        assertThat(RuleTests.lines(new UnusedLocalVariableRule(), """
                class Checker {
                    void check(HandlerMapping mapping, HttpServletRequest request) throws Exception {
                        HandlerExecutionChain handlerIgnored = mapping.getHandler(request);
                        HandlerExecutionChain handler = mapping.getHandler(request);
                    }
                }
                """)).containsExactly(4);
    }

    @Test
    void pathOfFileIsNotItsContent() {
        assertThat(RuleTests.lines(new FileContentInEntityRule(), """
                @Entity
                class FileInfo {
                    @Lob
                    @Column(name = "src_file_path")
                    private String srcFilePath;
                    @Lob
                    @Column(name = "file_content")
                    private String fileContent;
                }
                """)).hasSize(1);
    }

    @Test
    void transferObjectHasNoLazyRelations() {
        assertThat(RuleTests.lines(new RelationContainsInLoopRule(), """
                @Service
                class Orders {
                    private final OrderRepository repository;
                    @Transactional
                    void check(List<Order> orders, List<CardDto> cards, Item special, String text) {
                        for (Order order : orders) {
                            if (order.getItems().contains(special)) {
                                mark(order);
                            }
                            if (order.getCustomerName().contains(text)) {
                                mark(order);
                            }
                        }
                        for (CardDto cardDto : cards) {
                            if (cardDto.getSizes().contains(special)) {
                                mark(cardDto);
                            }
                        }
                    }
                }
                """)).containsExactly(7);
    }

    @Test
    void callForExistenceCheckNeedsNoResult() {
        StringBuilder used = new StringBuilder();
        for (int index = 0; index < 20; index++) {
            used.append("        if (accounts.findAccount(order) != null) { count++; }\n");
            used.append("        if (accounts.isActive(order)) { count++; }\n");
        }
        List<Violation> violations = RuleTests.checkProject(new UsuallyCheckedResultIgnoredRule(), Map.of(
                "Accounts.java", """
                        class Accounts {
                            private final AccountRepository repository;
                            Account findAccount(Order order) {
                                return repository.findByOrder(order).orElseThrow(() -> new NotFoundException(order));
                            }
                            boolean isActive(Order order) {
                                return order.total() > 0;
                            }
                        }
                        """,
                "Orders.java", """
                        class Orders {
                            private final Accounts accounts;
                            private int count;
                            void place(Order order) {
                        """ + used + """
                                accounts.findAccount(order);
                                accounts.isActive(order);
                            }
                        }
                        """));

        // findAccount без результата проверяет, что счет есть: метод сам бросит исключение
        assertThat(violations).extracting(Violation::line).containsExactly(46);
    }

    @Test
    void constantsHolderHasSingleResponsibility() {
        StringBuilder constants = new StringBuilder();
        for (int index = 0; index < 510; index++) {
            constants.append("    static final String CODE_").append(index).append(" = \"jr:").append(index).append("\";\n");
        }
        LargeClassRule rule = new LargeClassRule();

        assertThat(RuleTests.check(rule, "class RuleCodes {\n" + constants + "}\n")).isEmpty();
        assertThat(RuleTests.check(rule, "class Codes {\n" + constants
                + "    String first() {\n        return CODE_0;\n    }\n}\n")).hasSize(1);
    }

    @Test
    void jmixFillsAuditFieldsItself() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Application.java", """
                @SpringBootApplication
                class Application {
                }
                """);
        files.put("DbLog.java", """
                @JmixEntity
                @Entity
                class DbLog {
                    @CreatedDate
                    private Date createdDate;
                }
                """);
        files.put("Order.java", """
                @Entity
                class Order {
                    @CreatedDate
                    private Date createdDate;
                }
                """);

        assertThat(RuleTests.checkProject(new AuditingNotEnabledRule(), files))
                .extracting(violation -> violation.file().getFileName().toString())
                .containsExactly("Order.java");
    }
}
