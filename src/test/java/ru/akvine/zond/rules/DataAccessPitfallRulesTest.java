package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.concurrency.TransactionalWithAsyncRule;
import ru.akvine.zond.rules.logical.AuditingNotEnabledRule;
import ru.akvine.zond.rules.logical.PaginationWithoutSortRule;
import ru.akvine.zond.rules.logical.TransactionalRollbackForCheckedExceptionRule;
import ru.akvine.zond.rules.performance.DeleteOneByOneRule;
import ru.akvine.zond.rules.performance.PersistenceContextGrowthRule;
import ru.akvine.zond.rules.performance.UnboundedInClauseRule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:479, jr:481 - jr:483, jr:494 и дополнения к jr:3 и jr:103: страницы, транзакции и массовые операции
 */
class DataAccessPitfallRulesTest {
    private static final String ORDER_REPOSITORY = """
            interface OrderRepository extends JpaRepository<Order, Long> {
                Page<Order> findByStatus(String status, Pageable pageable);
                List<Order> findByStatus(String status);
                Page<Order> findByStatusOrderByIdAsc(String status, Pageable pageable);
                @Query("select o from Order o where o.status = :status order by o.id")
                Page<Order> sorted(String status, Pageable pageable);
                List<Order> findByIdIn(Collection<Long> ids);
                @Query("select o from Order o where o.customerId in (:customers)")
                List<Order> ofCustomers(List<Long> customers);
                Stream<Order> streamByStatus(String status);
                void deleteByStatus(String status);
                long removeAllByCreatedAtBefore(Instant moment);
                @Modifying
                @Query("delete from Order o where o.status = :status")
                void deleteByStatusInBatch(String status);
                void deleteById(Long id);
            }
            """;

    @Test
    void pagesWithoutOrderAreUnstable() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("OrderRepository.java", ORDER_REPOSITORY);
        files.put("OrderService.java", """
                @Service
                class OrderService {
                    private final OrderRepository orderRepository;
                    Page<Order> page(int page, int size) {
                        return orderRepository.findByStatus("NEW", PageRequest.of(page, size));
                    }
                    Page<Order> byVariable(int page, int size) {
                        Pageable pageable = PageRequest.of(page, size);
                        return orderRepository.findByStatus("NEW", pageable);
                    }
                    Page<Order> sortedByName(int page, int size) {
                        return orderRepository.findByStatusOrderByIdAsc("NEW", PageRequest.of(page, size));
                    }
                    Page<Order> sortedByQuery(int page, int size) {
                        Pageable pageable = PageRequest.of(page, size);
                        return orderRepository.sorted("NEW", pageable);
                    }
                    Page<Order> sortedHere(int page, int size) {
                        return orderRepository.findByStatus("NEW", PageRequest.of(page, size, Sort.by("id")));
                    }
                    Page<Order> first(int size) {
                        return orderRepository.findByStatus("NEW", PageRequest.of(0, size));
                    }
                    List<Order> raw(int size, int offset) {
                        return jdbcTemplate.query("select * from orders limit ? offset ?", mapper, size, offset);
                    }
                    List<Order> rawSorted(int size, int offset) {
                        return jdbcTemplate.query("select * from orders order by id limit ? offset ?", mapper, size, offset);
                    }
                }
                """);
        files.put("OrderController.java", """
                @RestController
                class OrderController {
                    @GetMapping("/orders")
                    Page<Order> list(Pageable pageable) {
                        return service.list(pageable);
                    }
                    @GetMapping("/sorted")
                    Page<Order> sorted(@PageableDefault(sort = "id") Pageable pageable) {
                        return service.list(pageable);
                    }
                }
                """);

        List<Violation> violations = RuleTests.checkProject(new PaginationWithoutSortRule(), files);

        // Порядок задан именем метода, запросом, Sort либо @PageableDefault; первая страница - просто "любые N строк"
        assertThat(violations).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("OrderController.java:4", "OrderService.java:5", "OrderService.java:8", "OrderService.java:25");
        assertThat(violations.get(0).message()).contains("Параметр 'pageable'", "@PageableDefault(sort = ");
        assertThat(violations.get(0).errorLevel()).isEqualTo(ErrorLevel.MINOR);
        assertThat(violations.get(0).confidenceOrDefault()).isEqualTo(Confidence.SUSPICION);
        assertThat(violations.get(1).message())
                .contains("'PageRequest.of(page, size)' - страница без сортировки", "на две страницы", "Sort.by");
        assertThat(violations.get(3).message()).contains("Запрос с OFFSET без ORDER BY");
    }

    @Test
    void transactionDoesNotReachParallelStreamAndSneakyThrows() {
        List<Violation> parallel = RuleTests.check(new TransactionalWithAsyncRule(), """
                @Service
                class OrderService {
                    @Transactional
                    public void recalculate(List<Order> orders) {
                        orders.parallelStream().forEach(order -> orderRepository.save(order));
                        orders.stream().parallel().map(this::enrich).toList();
                        int total = orders.parallelStream().mapToInt(Order::getTotal).sum();
                        orders.stream().forEach(order -> orderRepository.save(order));
                    }
                    public void plain(List<Order> orders) {
                        orders.parallelStream().forEach(orderRepository::save);
                    }
                    private Order enrich(Order order) {
                        return orderRepository.findById(order.getId()).orElse(order);
                    }
                }
                """);

        // Вычисления в параллельном стриме и работа с базой вне транзакции - не находки
        assertThat(parallel).extracting(Violation::line).containsExactly(5, 6);
        assertThat(parallel.get(0).message()).contains(
                "Параллельный стрим внутри @Transactional-метода 'recalculate'", "'orderRepository.save'",
                "LazyInitializationException");
        assertThat(parallel.get(1).message()).contains("'orderRepository.findById'");

        List<Violation> sneaky = RuleTests.check(new TransactionalRollbackForCheckedExceptionRule(), """
                @Service
                class ExportService {
                    @Transactional
                    @SneakyThrows
                    public void export() {
                    }
                    @Transactional(rollbackFor = Exception.class)
                    @SneakyThrows
                    public void safe() {
                    }
                    @SneakyThrows
                    public void plain() {
                    }
                }
                """);

        assertThat(sneaky).extracting(Violation::line).containsExactly(3);
        assertThat(sneaky.get(0).message()).contains("@SneakyThrows на @Transactional-методе 'export'", "rollbackFor");
    }

    @Test
    void batchInOneTransactionFillsPersistenceContext() {
        List<Violation> violations = RuleTests.check(new PersistenceContextGrowthRule(), """
                @Service
                class MigrationService {
                    @Transactional
                    public void migrate() {
                        int page = 0;
                        Page<Order> current;
                        do {
                            current = orderRepository.findAll(PageRequest.of(page++, 500, Sort.by("id")));
                            current.forEach(this::convert);
                        } while (current.hasNext());
                    }
                    @Transactional(readOnly = true)
                    public void export(Writer writer) {
                        try (Stream<Order> orders = orderRepository.streamByStatus("DONE")) {
                            orders.forEach(order -> write(writer, order));
                        }
                    }
                    @Transactional
                    public void migrateCarefully() {
                        int page = 0;
                        Page<Order> current;
                        do {
                            current = orderRepository.findAll(PageRequest.of(page++, 500, Sort.by("id")));
                            current.forEach(this::convert);
                            entityManager.flush();
                            entityManager.clear();
                        } while (current.hasNext());
                    }
                    public void withoutTransaction() {
                        int page = 0;
                        Page<Order> current;
                        do {
                            current = orderRepository.findAll(PageRequest.of(page++, 500, Sort.by("id")));
                        } while (current.hasNext());
                    }
                    @Transactional
                    public void small(List<Long> ids) {
                        for (Long id : ids) {
                            orderRepository.findById(id).ifPresent(this::convert);
                        }
                    }
                }
                """);

        // С clear() контекст не растет; без транзакции каждая страница живет в своей; цикл по списку - не обход таблицы
        assertThat(violations).extracting(Violation::line).containsExactly(7, 14);
        assertThat(violations.get(0).message())
                .contains("Постраничный обход таблицы в методе 'migrate'", "entityManager.clear()", "память растет");
        assertThat(violations.get(1).message()).contains("Потоковое чтение 'orderRepository.streamByStatus(");
    }

    @Test
    void bulkDeleteGoesRowByRow() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("OrderRepository.java", ORDER_REPOSITORY);
        files.put("CleanupService.java", """
                @Service
                class CleanupService {
                    private final OrderRepository orderRepository;
                    void cleanup(List<Order> stale, Set<String> names) {
                        orderRepository.deleteAll();
                        orderRepository.deleteAll(stale);
                        orderRepository.deleteAllInBatch();
                        orderRepository.deleteById(1L);
                        names.deleteAll();
                    }
                }
                """);

        List<Violation> violations = RuleTests.checkProject(new DeleteOneByOneRule(), files);

        // Удаление запросом, одной записи по id и deleteAllInBatch идут одним оператором
        assertThat(violations).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("OrderRepository.java:11", "OrderRepository.java:12", "CleanupService.java:5", "CleanupService.java:6");
        assertThat(violations.get(0).message()).contains("'deleteByStatus' без @Query", "по одной", "@Modifying");
        assertThat(violations.get(2).message()).contains("читает всю таблицу в память", "deleteAllInBatch()");
        assertThat(violations.get(3).message()).contains("deleteAllByIdInBatch(ids)");
    }

    @Test
    void auditFieldsNeedListenerAndEnabledAuditing() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Application.java", """
                @SpringBootApplication
                class Application {
                }
                """);
        files.put("Order.java", """
                @Entity
                class Order {
                    @Id
                    private Long id;
                    @CreatedDate
                    private Instant createdAt;
                    @CreatedBy
                    private String author;
                }
                """);
        files.put("Invoice.java", """
                @Entity
                class Invoice extends Audited {
                    private String number;
                }
                """);
        files.put("Audited.java", """
                @MappedSuperclass
                @EntityListeners(AuditingEntityListener.class)
                class Audited {
                    @LastModifiedDate
                    private Instant updatedAt;
                }
                """);

        AuditingNotEnabledRule rule = new AuditingNotEnabledRule();
        List<Violation> disabled = RuleTests.checkProject(rule, files);
        // Аудит не включен вовсе - об этом сообщается один раз
        assertThat(disabled).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("Order.java:5");
        assertThat(disabled.get(0).message()).contains("@CreatedDate в классе 'Order' не работает", "@EnableJpaAuditing");

        files.put("Application.java", """
                @SpringBootApplication
                @EnableJpaAuditing
                class Application {
                }
                """);
        List<Violation> enabled = RuleTests.checkProject(rule, files);
        // Аудит включен, но у Order нет слушателя и некому назвать автора; у Audited слушатель есть
        assertThat(enabled).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("Order.java:5", "Order.java:7");
        assertThat(enabled.get(0).message()).contains("@EntityListeners(AuditingEntityListener.class)");
        assertThat(enabled.get(1).message()).contains("AuditorAware");
        assertThat(enabled.get(1).errorLevel()).isEqualTo(ErrorLevel.MINOR);
    }

    @Test
    void inListGrowsWithData() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("OrderRepository.java", ORDER_REPOSITORY);
        files.put("OrderService.java", """
                @Service
                class OrderService {
                    private final OrderRepository orderRepository;
                    List<Order> related(String status) {
                        List<Order> orders = orderRepository.findByStatus(status);
                        List<Long> ids = orders.stream().map(Order::getId).toList();
                        return orderRepository.findByIdIn(ids);
                    }
                    List<Order> direct(String status) {
                        return orderRepository.ofCustomers(customerRepository.findActiveIds());
                    }
                    List<Order> inParts(String status) {
                        List<Long> ids = orderRepository.findByStatus(status).stream().map(Order::getId).toList();
                        List<Order> result = new ArrayList<>();
                        for (List<Long> part : Lists.partition(ids, 500)) {
                            result.addAll(orderRepository.findByIdIn(part));
                        }
                        return result;
                    }
                    List<Order> few(Long first, Long second) {
                        return orderRepository.findByIdIn(List.of(first, second));
                    }
                }
                """);
        files.put("OrderController.java", """
                @RestController
                class OrderController {
                    @PostMapping("/orders/lookup")
                    List<Order> lookup(@RequestBody List<Long> ids) {
                        return orderRepository.findAllById(ids);
                    }
                    @PostMapping("/orders/limited")
                    List<Order> limited(@RequestBody @Size(max = 100) List<Long> ids) {
                        return orderRepository.findAllById(ids);
                    }
                }
                """);

        UnboundedInClauseRule rule = new UnboundedInClauseRule();
        List<Violation> violations = RuleTests.checkProject(rule, files);

        // Список, разбитый на части, ограниченный @Size либо собранный из двух значений, - не находка
        assertThat(violations).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("OrderService.java:7", "OrderController.java:5");
        assertThat(violations.get(0).message()).contains(
                "Список для IN в 'orderRepository.findByIdIn(...)'", "'ids' собран из результата", "1000", "Lists.partition");
        assertThat(violations.get(1).message()).contains("'ids' приходит из HTTP-запроса");
        assertThat(rule.confidence()).isEqualTo(Confidence.SUSPICION);
    }
}
