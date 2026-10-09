package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.exceptions.BroadCatchRule;
import ru.akvine.zond.rules.exceptions.GenericExceptionRule;
import ru.akvine.zond.rules.performance.LoggingInLoopRule;
import ru.akvine.zond.rules.performance.RepositoryCallInLoopRule;
import ru.akvine.zond.rules.streams.ToMapWithoutMergeRule;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила учитывают, в каких обстоятельствах выполняется код: при запуске приложения, в обработчике ошибки,
 * в цикле повторных попыток, на границе задачи
 */
class CodeContextRulesTest {

    @Test
    void errorReportsInLoopAreNotFlood() {
        assertThat(RuleTests.lines(new LoggingInLoopRule(), """
                class Sample {
                    void run(List<Order> orders) {
                        for (Order order : orders) {
                            log.info("Processing {}", order);
                            try {
                                process(order);
                            } catch (IOException e) {
                                log.error("Failed {}", order, e);
                                log.info("Skipped {}", order);
                            }
                            if (order.isBroken()) {
                                log.warn("Broken {}", order);
                                log.info("Checked {}", order);
                            }
                            log.warn("Seen {}", order);
                        }
                        orders.forEach(order -> {
                            if (order.isLate()) {
                                log.error("Late {}", order);
                            }
                        });
                    }
                }
                """))
                // Запись в catch и warn / error под условием сообщают об ошибке; info под условием и warn
                // на каждом элементе - по-прежнему поток записей
                .containsExactly(4, 13, 15);
    }

    @Test
    void retryLoopRepeatsAttemptsNotData() {
        String code = """
                class Sample {
                    Order load(Long id, List<Long> ids, List<Entry> entries) throws Exception {
                        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                            log.info("Attempt {}", attempt);
                            if (orderRepository.existsById(id)) {
                                return orderRepository.findById(id).orElseThrow();
                            }
                        }
                        int retries = 0;
                        while (retries++ < 3) {
                            log.info("Retry {}", retries);
                            orderRepository.touch(id);
                        }
                        while (true) {
                            try {
                                log.info("Loading {}", id);
                                return orderRepository.findById(id).orElseThrow();
                            } catch (TransientException e) {
                                Thread.sleep(100);
                            }
                        }
                    }
                    void process(List<Long> ids, List<Entry> entries) {
                        for (int i = 0; i < entries.size(); i++) {
                            log.info("Entry {}", i);
                            orderRepository.save(entries.get(i));
                        }
                        for (Long id : ids) {
                            for (int attempt = 0; attempt < 3; attempt++) {
                                log.info("Attempt {} for {}", attempt, id);
                                orderRepository.touch(id);
                            }
                        }
                    }
                }
                """;

        // entries - не "tries": обычный перебор данных. Попытки внутри перебора умножаются на число элементов
        assertThat(RuleTests.lines(new LoggingInLoopRule(), code)).containsExactly(25, 30);
        assertThat(RuleTests.lines(new RepositoryCallInLoopRule(), code)).containsExactly(26, 31);
    }

    @Test
    void broadCatchIsAllowedAtTaskBoundaryWhenExceptionIsKept() {
        assertThat(RuleTests.lines(new BroadCatchRule(), """
                class Jobs implements Runnable {
                    @Scheduled(fixedDelay = 1000)
                    void cleanup() {
                        try {
                            store.deleteExpired();
                        } catch (Exception e) {
                            log.error("Cleanup failed", e);
                        }
                    }
                    @Scheduled(fixedDelay = 1000)
                    void silent() {
                        try {
                            store.deleteExpired();
                        } catch (Exception e) {
                            log.warn("Cleanup failed: {}", e.getMessage());
                        }
                    }
                    @KafkaListener(topics = "orders")
                    void onOrder(String payload) {
                        try {
                            handle(payload);
                        } catch (Exception e) {
                            errorHandler.handle(payload, e);
                        }
                    }
                    @Override
                    public void run() {
                        try {
                            work();
                        } catch (Throwable e) {
                            log.error("Worker failed", e);
                        }
                    }
                    void each(List<File> files) {
                        for (File file : files) {
                            try {
                                delete(file);
                            } catch (Exception e) {
                                log.error("Cannot delete {}", file, e);
                            }
                        }
                        executor.submit(() -> {
                            try {
                                work();
                            } catch (Exception e) {
                                log.error("Task failed", e);
                            }
                        });
                    }
                    String regular(String url) {
                        try {
                            return client.call(url);
                        } catch (Exception e) {
                            log.error("Call failed", e);
                            return null;
                        }
                    }
                }
                """))
                // На границе задачи, но исключение пропало (остался только текст) - находка; обычный метод - находка
                .containsExactly(14, 53);
    }

    @Test
    void duplicateKeyAtStartupIsConfigurationError() {
        assertThat(RuleTests.lines(new ToMapWithoutMergeRule(), """
                @Service
                class Registry {
                    private static final Map<String, Mode> MODES =
                            Arrays.stream(Mode.values()).collect(Collectors.toMap(Mode::getCode, mode -> mode));
                    private final Map<Type, Handler> handlers;
                    private Map<String, Rate> rates;
                    Registry(List<Handler> all) {
                        this.handlers = all.stream().collect(Collectors.toMap(Handler::getType, handler -> handler));
                    }
                    @PostConstruct
                    void load() {
                        rates = index(repository.findAll());
                    }
                    private Map<String, Rate> index(List<Rate> list) {
                        return list.stream().collect(Collectors.toMap(Rate::getCode, rate -> rate));
                    }
                    Map<Long, Order> byId(List<Order> orders) {
                        return orders.stream().collect(Collectors.toMap(Order::getId, order -> order));
                    }
                }
                @Configuration
                class Beans {
                    @Bean
                    Map<Type, Exporter> exporters(List<Exporter> all) {
                        return all.stream().collect(Collectors.toMap(Exporter::getType, exporter -> exporter));
                    }
                }
                class Report {
                    private final Map<Long, Row> rows;
                    Report(List<Row> all) {
                        this.rows = all.stream().collect(Collectors.toMap(Row::getId, row -> row));
                    }
                }
                enum Mode {
                    FAST, SLOW;
                    private static final Map<String, Mode> BY_NAME =
                            Stream.of(values()).collect(Collectors.toMap(Mode::name, mode -> mode));
                }
                """))
                // Обычный метод работает с данными; объект Report создают во время работы, а не при запуске
                .containsExactly(18, 31);
    }

    @Test
    void containerAndLambdaSignaturesMayThrowException() {
        assertThat(RuleTests.lines(new GenericExceptionRule(), """
                @Configuration
                class Beans {
                    @Bean
                    DataSource dataSource() throws Exception { return create(); }
                    @PostConstruct
                    void register() throws Exception {}
                    @PreDestroy
                    void close() throws Exception {}
                    void regular() throws Exception {}
                    private void helper() throws Exception {}
                    @PostConstruct
                    void init() throws Exception { helper(); }
                }
                @FunctionalInterface
                interface Action {
                    void call() throws Exception;
                }
                interface Loader {
                    String load(String key) throws Exception;
                    default String name() { return "loader"; }
                }
                interface Storage {
                    void save(String key) throws Exception;
                    void delete(String key) throws Exception;
                }
                """))
                // helper вызывается только при запуске; у Storage два метода - это не интерфейс для лямбды
                .containsExactly(9, 23, 24);
    }
}
