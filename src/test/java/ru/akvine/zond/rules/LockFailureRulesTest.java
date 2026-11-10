package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.exceptions.OptimisticLockWithoutRetryRule;
import ru.akvine.zond.rules.exceptions.PessimisticLockWithoutHandlingRule;
import ru.akvine.zond.rules.exceptions.StrictIsolationWithoutRetryRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:456 - jr:458: блокировка строки, строгая изоляция и версия сущности без готовности к отказу
 */
class LockFailureRulesTest {
    private static final String ACCOUNT = """
            @Entity
            class Account {
                @Id
                private Long id;
                private BigDecimal balance;
            }
            """;
    private static final String ACCOUNT_REPOSITORY = """
            interface AccountRepository extends JpaRepository<Account, Long> {
                @Lock(LockModeType.PESSIMISTIC_WRITE)
                @Query("select a from Account a where a.id = :id")
                Optional<Account> findByIdForUpdate(Long id);
                @Lock(LockModeType.PESSIMISTIC_WRITE)
                @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
                List<Account> findTop10ByStatus(String status);
                @Query(value = "select * from accounts where id = :id for update", nativeQuery = true)
                Account lockNative(Long id);
                @Query(value = "select * from accounts where status = 'NEW' for update skip locked", nativeQuery = true)
                List<Account> nextBatch();
                Optional<Account> findByNumber(String number);
            }
            """;
    private static final String TRANSFER_SERVICE = """
            class TransferService {
                private AccountRepository accountRepository;
                private EntityManager entityManager;
                @Transactional
                public void withdraw(Long id, BigDecimal amount) {
                    Account account = accountRepository.findByIdForUpdate(id).orElseThrow();
                    account.setBalance(account.getBalance().subtract(amount));
                }
                @Transactional
                public void nativeLock(Long id) {
                    Account account = accountRepository.lockNative(id);
                }
                @Transactional
                public void viaEntityManager(Account account) {
                    entityManager.lock(account, LockModeType.PESSIMISTIC_WRITE);
                    Account fresh = entityManager.find(Account.class, account.getId(), LockModeType.PESSIMISTIC_READ);
                }
                @Transactional
                public void caught(Long id) {
                    try {
                        accountRepository.findByIdForUpdate(id);
                    } catch (PessimisticLockingFailureException e) {
                        throw new AccountBusyException(e);
                    }
                }
                @Retryable(retryFor = CannotAcquireLockException.class)
                @Transactional
                public void retried(Long id) {
                    accountRepository.findByIdForUpdate(id);
                }
                @Transactional
                public void skipLocked() {
                    accountRepository.nextBatch();
                    accountRepository.findTop10ByStatus("NEW");
                }
                @Transactional
                public void plainRead(String number) {
                    accountRepository.findByNumber(number);
                }
                @Transactional
                public void handledByCaller(Long id) {
                    accountRepository.findByIdForUpdate(id);
                }
                @Transactional
                public void wrappedByCaller(Long id) {
                    accountRepository.findByIdForUpdate(id);
                }
                @Transactional
                public void optimistic(Account account) {
                    entityManager.lock(account, LockModeType.OPTIMISTIC);
                }
                @Transactional
                public void exposedByCaller(Long id) {
                    lockQuietly(id);
                }
                private void lockQuietly(Long id) {
                    accountRepository.findByIdForUpdate(id);
                }
            }
            """;
    private static final String TRANSFER_CONTROLLER = """
            class TransferController {
                private TransferService transferService;
                private RetryTemplate retryTemplate;
                public ResponseEntity<Void> handled(Long id) {
                    try {
                        transferService.handledByCaller(id);
                    } catch (CannotAcquireLockException e) {
                        return ResponseEntity.status(409).build();
                    }
                    return ResponseEntity.ok().build();
                }
                public void wrapped(Long id) {
                    retryTemplate.execute(context -> {
                        transferService.wrappedByCaller(id);
                        return null;
                    });
                }
                public void exposed(Long id) {
                    transferService.exposedByCaller(id);
                }
            }
            """;

    private static final String ORDER = """
            @Entity
            class Order extends BaseEntity {
                @Id
                private Long id;
                private String status;
                private String title;
            }
            """;
    private static final String BASE_ENTITY = """
            @MappedSuperclass
            class BaseEntity {
                @Version
                private Long version;
            }
            """;
    private static final String NOTE = """
            @Entity
            class Note {
                @Id
                private Long id;
                private String text;
            }
            """;
    private static final String ORDER_REPOSITORY = """
            interface OrderRepository extends JpaRepository<Order, Long> {
                @Lock(LockModeType.PESSIMISTIC_WRITE)
                Optional<Order> findWithLockById(Long id);
                Optional<Order> findByTitle(String title);
            }
            """;
    private static final String NOTE_REPOSITORY = "interface NoteRepository extends JpaRepository<Note, Long> {}";
    private static final String ORDER_SERVICE = """
            class OrderService {
                private OrderRepository orderRepository;
                private NoteRepository noteRepository;
                private OrderMapper orderMapper;
                @Transactional
                public void cancel(Long id) {
                    Order order = orderRepository.findById(id).orElseThrow();
                    order.setStatus("CANCELLED");
                }
                public void rename(Long id, String title) {
                    Order order = orderRepository.findById(id).orElseThrow();
                    order.setTitle(title);
                    orderRepository.save(order);
                }
                @Transactional
                public void remove(Long id) {
                    Order order = orderRepository.getReferenceById(id);
                    orderRepository.delete(order);
                }
                public void patch(Long id, OrderRequest request) {
                    Order order = orderRepository.findByTitle(request.title()).orElseThrow();
                    orderMapper.update(order, request);
                    orderRepository.save(order);
                }
                @Retryable(retryFor = OptimisticLockingFailureException.class)
                @Transactional
                public void retried(Long id) {
                    Order order = orderRepository.findById(id).orElseThrow();
                    order.setStatus("PAID");
                }
                @Transactional
                public void locked(Long id) {
                    Order order = orderRepository.findWithLockById(id).orElseThrow();
                    order.setStatus("PAID");
                }
                @Transactional
                public Order create(String title) {
                    Order order = new Order(title);
                    order.setStatus("NEW");
                    return orderRepository.save(order);
                }
                @Transactional(readOnly = true)
                public String status(Long id) {
                    Order order = orderRepository.findById(id).orElseThrow();
                    log.info("order {}", order);
                    order.validate();
                    return order.getStatus();
                }
                @Transactional
                public void note(Long id) {
                    Note note = noteRepository.findById(id).orElseThrow();
                    note.setText("x");
                }
                @Transactional
                public void caught(Long id) {
                    Order order = orderRepository.findById(id).orElseThrow();
                    order.setStatus("DONE");
                    try {
                        orderRepository.saveAndFlush(order);
                    } catch (ObjectOptimisticLockingFailureException e) {
                        throw new OrderChangedException(e);
                    }
                }
                @Transactional
                public void handledByCaller(Long id) {
                    Order order = orderRepository.findById(id).orElseThrow();
                    order.setStatus("SENT");
                }
                public void onlyRead(Long id) {
                    Order order = orderRepository.findById(id).orElseThrow();
                    order.validate();
                    sender.send(order);
                }
            }
            """;
    private static final String ORDER_CONTROLLER = """
            class OrderController {
                private OrderService orderService;
                public ResponseEntity<Void> send(Long id) {
                    try {
                        orderService.handledByCaller(id);
                    } catch (OptimisticLockingFailureException e) {
                        return ResponseEntity.status(409).build();
                    }
                    return ResponseEntity.ok().build();
                }
            }
            """;

    @TempDir
    Path dir;

    @Test
    void pessimisticLockMustExpectFailure() {
        ProjectFixture project = new ProjectFixture(dir)
                .source("Account.java", ACCOUNT)
                .source("AccountRepository.java", ACCOUNT_REPOSITORY)
                .source("TransferService.java", TRANSFER_SERVICE)
                .source("TransferController.java", TRANSFER_CONTROLLER);
        PessimisticLockWithoutHandlingRule rule = new PessimisticLockWithoutHandlingRule();

        // Не считаются: перехват рядом и у вызывающего, @Retryable, RetryTemplate вокруг вызова, SKIP LOCKED,
        // запрос без блокировки и оптимистичный режим
        assertThat(project.lines(rule)).containsExactly(
                "TransferService.java:11", "TransferService.java:15", "TransferService.java:16",
                "TransferService.java:57", "TransferService.java:6");

        List<Violation> violations = rule.checkProject(project.context().sources());
        assertThat(violations.get(0).message())
                .contains("@Lock(PESSIMISTIC_WRITE) на 'AccountRepository.findByIdForUpdate'", "LockTimeoutException");
        assertThat(violations.get(1).message()).contains("запрос FOR UPDATE на 'AccountRepository.lockNative'");
        assertThat(violations.get(2).message()).contains("entityManager.lock(..., PESSIMISTIC_WRITE)");
    }

    @Test
    void applicationWideHandlerAnswersForEveryLock() {
        ProjectFixture project = new ProjectFixture(dir)
                .source("Account.java", ACCOUNT)
                .source("AccountRepository.java", ACCOUNT_REPOSITORY)
                .source("TransferService.java", TRANSFER_SERVICE)
                .source("Errors.java", """
                        @RestControllerAdvice
                        class Errors {
                            @ExceptionHandler(PessimisticLockingFailureException.class)
                            ResponseEntity<String> busy() {
                                return ResponseEntity.status(409).body("Запись занята, повторите позже");
                            }
                        }
                        """);

        assertThat(project.lines(new PessimisticLockWithoutHandlingRule())).isEmpty();

        // Обработчик общего исключения отдает ту же ошибку 500: готовностью к отказу он не считается
        ProjectFixture broad = new ProjectFixture(dir)
                .source("AccountRepository.java", ACCOUNT_REPOSITORY)
                .source("TransferService.java", TRANSFER_SERVICE)
                .source("Errors.java", """
                        @RestControllerAdvice
                        class Errors {
                            @ExceptionHandler(Exception.class)
                            ResponseEntity<String> failed() {
                                return ResponseEntity.status(500).body("Ошибка");
                            }
                        }
                        """);
        assertThat(broad.lines(new PessimisticLockWithoutHandlingRule())).isNotEmpty();
    }

    @Test
    void strictIsolationNeedsRetry() {
        ProjectFixture project = new ProjectFixture(dir)
                .source("PaymentService.java", """
                        class PaymentService {
                            @Transactional(isolation = Isolation.SERIALIZABLE)
                            public void transfer(Long from, Long to) {}
                            @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
                            public Report report() { return null; }
                            @Retryable(retryFor = CannotAcquireLockException.class, maxAttempts = 3)
                            @Transactional(isolation = Isolation.SERIALIZABLE)
                            public void retried() {}
                            @Transactional(isolation = Isolation.READ_COMMITTED)
                            public void relaxed() {}
                            @Transactional(isolation = Isolation.REPEATABLE_READ)
                            public void wrapped() {}
                            @Transactional(isolation = REPEATABLE_READ)
                            public void exposed() {}
                            @Transactional(isolation = Isolation.SERIALIZABLE)
                            private void notProxied() {}
                            @Transactional
                            public void plain() {}
                        }
                        """)
                .source("PaymentFacade.java", """
                        class PaymentFacade {
                            private PaymentService paymentService;
                            private RetryTemplate retryTemplate;
                            public void pay() {
                                retryTemplate.execute(context -> {
                                    paymentService.wrapped();
                                    return null;
                                });
                            }
                            public void expose() {
                                paymentService.exposed();
                            }
                        }
                        """)
                .source("LedgerService.java", """
                        @Transactional(isolation = Isolation.SERIALIZABLE)
                        class LedgerService {
                            public void post() {}
                            public void reverse() {}
                            void internal() {}
                        }
                        """);
        StrictIsolationWithoutRetryRule rule = new StrictIsolationWithoutRetryRule();

        // readOnly, @Retryable, RetryTemplate у вызывающего и обычная изоляция повтора не требуют
        assertThat(project.lines(rule)).containsExactly(
                "LedgerService.java:1", "PaymentService.java:13", "PaymentService.java:2");

        List<Violation> violations = rule.checkProject(project.context().sources());
        assertThat(violations.get(0).message()).contains("метода 'transfer'", "SERIALIZABLE", "ошибка сериализации");
        assertThat(violations.get(1).message()).contains("'exposed'", "REPEATABLE_READ");
        // Аннотация на классе - одна находка на все его открытые методы
        assertThat(violations.get(2).message()).contains("Транзакции класса", "'post', 'reverse'");
    }

    @Test
    void versionedEntityUpdateNeedsRetryOrHandling() {
        ProjectFixture project = orders().source("OrderController.java", ORDER_CONTROLLER);
        OptimisticLockWithoutRetryRule rule = new OptimisticLockWithoutRetryRule();

        // Не считаются: @Retryable, чтение с блокировкой строки, вставка новой записи, чтение без изменения,
        // сущность без @Version, перехват рядом с сохранением и у вызывающего
        assertThat(project.lines(rule)).containsExactly(
                "OrderService.java:13", "OrderService.java:18", "OrderService.java:23", "OrderService.java:8");

        List<Violation> violations = rule.checkProject(project.context().sources());
        assertThat(violations.get(0).message())
                .contains("Сущность 'Order' с @Version", "'cancel'", "order.setStatus(...)", "коммит транзакции");
        assertThat(violations.get(1).message()).contains("'rename'", "orderRepository.save(...)", "сохранение закончится");
        assertThat(violations.get(2).message()).contains("orderRepository.delete(...)");
    }

    @Test
    void conflictHandlerAnswersForEveryUpdate() {
        ProjectFixture project = orders().source("Errors.java", """
                @RestControllerAdvice
                class Errors {
                    @ExceptionHandler
                    ResponseEntity<String> changed(ObjectOptimisticLockingFailureException e) {
                        return ResponseEntity.status(409).body("Запись уже изменили");
                    }
                }
                """);

        assertThat(project.lines(new OptimisticLockWithoutRetryRule())).isEmpty();
    }

    @Test
    void entitiesWithoutVersionAreNotChecked() {
        ProjectFixture project = new ProjectFixture(dir)
                .source("Note.java", NOTE)
                .source("NoteRepository.java", NOTE_REPOSITORY)
                .source("OrderService.java", ORDER_SERVICE);

        assertThat(project.lines(new OptimisticLockWithoutRetryRule())).isEmpty();
    }

    private ProjectFixture orders() {
        return new ProjectFixture(dir)
                .source("BaseEntity.java", BASE_ENTITY)
                .source("Order.java", ORDER)
                .source("Note.java", NOTE)
                .source("OrderRepository.java", ORDER_REPOSITORY)
                .source("NoteRepository.java", NOTE_REPOSITORY)
                .source("OrderService.java", ORDER_SERVICE);
    }
}
