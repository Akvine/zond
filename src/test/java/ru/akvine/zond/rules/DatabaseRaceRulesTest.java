package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.concurrency.CheckThenInsertWithoutUniqueRule;
import ru.akvine.zond.rules.concurrency.LostUpdateRule;
import ru.akvine.zond.rules.files.DbSchema;
import ru.akvine.zond.rules.logical.CaughtExceptionInTransactionRule;
import ru.akvine.zond.rules.logical.EnumLongerThanColumnRule;
import ru.akvine.zond.rules.logical.SizeConstraintExceedsColumnRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:369 - jr:373: гонки при записи в БД и расхождения кода со схемой, которые всплывают ошибкой базы
 */
class DatabaseRaceRulesTest {
    private static final String USER = """
            @Entity
            @Table(name = "users")
            class User {
                @Id
                private Long id;
                private String email;
                @Column(unique = true)
                private String login;
                private String phone;
                @ManyToOne
                private Tenant tenant;
                private String code;
            }
            """;
    private static final String USER_REPOSITORY = """
            interface UserRepository extends JpaRepository<User, Long> {
                boolean existsByEmail(String email);
                boolean existsByLogin(String login);
                Optional<User> findByPhone(String phone);
                boolean existsByCodeAndTenantId(String code, Long tenantId);
                List<User> findByEmailContaining(String part);
            }
            """;
    private static final String USER_SERVICE = """
            class UserService {
                private UserRepository userRepository;
                void register(String email) {
                    if (userRepository.existsByEmail(email)) {
                        throw new IllegalStateException("exists");
                    }
                    userRepository.save(new User(email));
                }
                void registerByLogin(String login) {
                    if (userRepository.existsByLogin(login)) {
                        throw new IllegalStateException("exists");
                    }
                    userRepository.save(new User(login));
                }
                void registerByPhone(String phone) {
                    Optional<User> found = userRepository.findByPhone(phone);
                    if (found.isPresent()) {
                        return;
                    }
                    userRepository.save(new User(phone));
                }
                void update(String phone) {
                    User user = userRepository.findByPhone(phone).orElseThrow();
                    user.setActive(true);
                    userRepository.save(user);
                }
                void registerInTenant(String code, Long tenantId) {
                    if (userRepository.existsByCodeAndTenantId(code, tenantId)) {
                        return;
                    }
                    userRepository.save(new User(code));
                }
                void handled(String email) {
                    if (!userRepository.existsByEmail(email)) {
                        try {
                            userRepository.save(new User(email));
                        } catch (DataIntegrityViolationException e) {
                            log.info("duplicate");
                        }
                    }
                }
                void search(String part) {
                    if (userRepository.findByEmailContaining(part).isEmpty()) {
                        userRepository.save(new User(part));
                    }
                }
                void lockedRegister(String email) {
                    lock.lock();
                    try {
                        if (userRepository.existsByEmail(email)) {
                            return;
                        }
                        userRepository.save(new User(email));
                    } finally {
                        lock.unlock();
                    }
                }
                void touch(String email) {
                    Optional<User> found = userRepository.findByEmail(email);
                    if (found.isPresent()) {
                        User user = found.get();
                        user.setActive(true);
                        userRepository.save(user);
                    }
                }
                void upsert(String email) {
                    Optional<User> found = userRepository.findByEmail(email);
                    User user;
                    if (found.isEmpty()) {
                        user = new User(email);
                    } else {
                        user = found.get();
                    }
                    userRepository.save(user);
                }
                void registerGiven(User user) {
                    if (userRepository.existsByEmail(user.getEmail())) {
                        throw new IllegalStateException("exists");
                    }
                    userRepository.save(user);
                }
            }
            """;
    private static final String ORDER = """
            @Entity
            @Table(name = "orders")
            class Order {
                @Id
                private Long id;
                @Enumerated(EnumType.STRING)
                private OrderStatus status;
                @Enumerated(EnumType.STRING)
                @Column(length = 30)
                private OrderStatus previousStatus;
                @Enumerated
                private OrderStatus ordinalStatus;
                @Enumerated(EnumType.STRING)
                @Column(name = "kind", length = 5)
                private OrderKind kind;
                @Size(max = 200)
                private String title;
                @Size(max = 50)
                private String code;
                @Length(max = 40)
                @Column(length = 20)
                private String note;
            }
            """;
    private static final String ORDERS_TABLE = """
            create table orders (
                id bigint primary key,
                status varchar(20),
                previous_status varchar(40),
                ordinal_status varchar(5),
                kind varchar(10),
                title varchar(100),
                code varchar(50),
                note text
            );
            """;

    @TempDir
    Path dir;

    @Test
    void schemaRemembersUniqueKeys() {
        ProjectFixture project = new ProjectFixture(dir).write("db/migration/V1__init.sql", """
                create table users (
                    id bigint primary key,
                    email varchar(100) unique,
                    phone varchar(20),
                    code varchar(10),
                    tenant_id bigint,
                    nick varchar(30),
                    old_name varchar(30),
                    constraint uq_code unique (code, tenant_id)
                );
                create unique index concurrently if not exists ux_phone on users using btree (lower(phone));
                alter table users add column passport varchar(20) unique;
                alter table users add constraint uq_old unique (old_name);
                alter table users rename column old_name to legal_name;
                create index ix_nick on users (nick);
                create table links (a bigint, b bigint, primary key (a, b));
                create table tokens (
                    id bigint primary key, value varchar(64), owner varchar(64), device varchar(64),
                    constraint uq_value unique (value)
                );
                create unique index ux_owner on tokens (owner);
                create unique index ux_device on tokens (device);
                drop index ux_owner;
                create index ux_owner on tokens (owner);
                alter table tokens drop constraint uq_value;
                """);

        DbSchema schema = DbSchema.of(project.context());
        DbSchema.Table users = schema.find("users").orElseThrow();

        assertThat(users.isUnique(List.of("id"))).isTrue();
        assertThat(users.isUnique(List.of("email"))).isTrue();
        // Индекс по выражению защищает ту же колонку
        assertThat(users.isUnique(List.of("phone"))).isTrue();
        assertThat(users.isUnique(List.of("passport"))).isTrue();
        // Ограничение переезжает вместе с переименованной колонкой
        assertThat(users.isUnique(List.of("legalName"))).isTrue();
        assertThat(users.isUnique(List.of("oldName"))).isFalse();
        // Составной ключ защищает только все свои колонки вместе; лишняя колонка в условии ему не мешает
        assertThat(users.isUnique(List.of("code"))).isFalse();
        assertThat(users.isUnique(List.of("code", "tenantId"))).isTrue();
        assertThat(users.isUnique(List.of("code", "tenant_id", "nick"))).isTrue();
        // Обычный индекс дубликатов не запрещает
        assertThat(users.isUnique(List.of("nick"))).isFalse();
        assertThat(schema.find("links").orElseThrow().isUnique(List.of("a", "b"))).isTrue();
        assertThat(schema.find("links").orElseThrow().isUnique(List.of("a"))).isFalse();
        // Удаленный индекс и снятое ограничение больше ничего не защищают; остальные остаются
        DbSchema.Table tokens = schema.find("tokens").orElseThrow();
        assertThat(tokens.isUnique(List.of("owner"))).isFalse();
        assertThat(tokens.isUnique(List.of("value"))).isFalse();
        assertThat(tokens.isUnique(List.of("device"))).isTrue();
        assertThat(tokens.isUnique(List.of("id"))).isTrue();
    }

    @Test
    void insertAfterExistenceCheckNeedsUniqueConstraint() {
        CheckThenInsertWithoutUniqueRule rule = new CheckThenInsertWithoutUniqueRule();
        ProjectFixture withSchema = new ProjectFixture(dir.resolve("schema"))
                .source("User.java", USER)
                .source("UserRepository.java", USER_REPOSITORY)
                .source("UserService.java", USER_SERVICE)
                .write("db/migration/V1__init.sql", """
                        create table users (
                            id bigint primary key, email varchar(100), login varchar(50), phone varchar(20),
                            tenant_id bigint, code varchar(10)
                        );
                        create unique index ux_users_phone on users (phone);
                        alter table users add constraint uq_code unique (code, tenant_id);
                        """);

        // login объявлен уникальным в сущности, phone и (code, tenant) защищены в базе; обновление найденной записи,
        // перехват ошибки базы, поиск по части строки и работа под блокировкой - не "проверил и вставил".
        // 67 - "найти или создать": вставка там есть; 77 - после existsBy сохраняют заведомо новое
        List<Violation> bySchema = withSchema.check(rule);
        assertThat(bySchema).extracting(Violation::line).containsExactly(4, 67, 77);
        assertThat(bySchema.get(0).confidence()).isEqualTo(Confidence.PROBABLE);
        assertThat(bySchema.get(0).message()).contains("existsByEmail").contains("userRepository.save").contains("'users'");

        // Без миграций об ограничениях в базе ничего не известно: остается только подозрение
        ProjectFixture withoutSchema = new ProjectFixture(dir.resolve("plain"))
                .source("User.java", USER)
                .source("UserRepository.java", USER_REPOSITORY)
                .source("UserService.java", USER_SERVICE);
        List<Violation> byCode = withoutSchema.check(rule);
        assertThat(byCode).extracting(Violation::line).containsExactly(4, 16, 28, 67, 77);
        assertThat(byCode).extracting(Violation::confidence).containsOnly(Confidence.SUSPICION);
    }

    @Test
    void readModifyWriteNeedsLockOrVersion() {
        ProjectFixture project = new ProjectFixture(dir)
                .source("Account.java", """
                        @Entity
                        class Account {
                            @Id
                            private Long id;
                            private BigDecimal balance;
                            private int visits;
                            private String note;
                        }
                        """)
                .source("Counter.java", """
                        @Entity
                        class Counter {
                            @Id
                            private Long id;
                            private long value;
                            @Version
                            private long version;
                        }
                        """)
                .source("AccountRepository.java", """
                        interface AccountRepository extends JpaRepository<Account, Long> {
                            @Lock(LockModeType.PESSIMISTIC_WRITE)
                            Optional<Account> findByNumber(String number);
                        }
                        """)
                .source("AccountService.java", """
                        class AccountService {
                            private AccountRepository accountRepository;
                            private CounterRepository counterRepository;
                            void deposit(Long id, BigDecimal amount) {
                                Account account = accountRepository.findById(id).orElseThrow();
                                account.setBalance(account.getBalance().add(amount));
                            }
                            void visit(Long id) {
                                Account account = accountRepository.findById(id).orElseThrow();
                                account.setVisits(account.getVisits() + 1);
                                account.setNote(account.getNote() + "!");
                                account.setNote("visited");
                            }
                            void locked(String number, BigDecimal amount) {
                                Account account = accountRepository.findByNumber(number).orElseThrow();
                                account.setBalance(account.getBalance().subtract(amount));
                            }
                            void versioned(Long id) {
                                Counter counter = counterRepository.findById(id).orElseThrow();
                                counter.setValue(counter.getValue() + 1);
                            }
                            void fromParameter(Account account) {
                                account.setVisits(account.getVisits() + 1);
                            }
                            void notEntity(Long id) {
                                Dto dto = accountRepository.findDto(id);
                                dto.setTotal(dto.getTotal() + 1);
                            }
                            synchronized void guarded(Long id) {
                                Account account = accountRepository.findById(id).orElseThrow();
                                account.setVisits(account.getVisits() + 1);
                            }
                        }
                        """);

        // Чтение с @Lock, сущность с @Version, параметр неизвестного происхождения, не сущность и метод
        // под synchronized в отчет не попадают; приклеивание строки - не счетчик
        assertThat(project.lines(new LostUpdateRule())).containsExactly("AccountService.java:10", "AccountService.java:6");
    }

    @Test
    void exceptionFromJoinedTransactionMustNotBeSwallowed() {
        ProjectFixture project = new ProjectFixture(dir)
                .source("OrderService.java", """
                        class OrderService {
                            private PaymentService paymentService;
                            private OrderRepository orderRepository;
                            private AuditService auditService;
                            @Transactional
                            public void place(Order order) {
                                orderRepository.save(order);
                                try {
                                    paymentService.charge(order);
                                } catch (Exception e) {
                                    log.warn("payment failed", e);
                                }
                            }
                            @Transactional
                            public void rethrows(Order order) {
                                try {
                                    paymentService.charge(order);
                                } catch (RuntimeException e) {
                                    throw new OrderException(e);
                                }
                            }
                            @Transactional
                            public void separate(Order order) {
                                try {
                                    auditService.record(order);
                                } catch (Exception e) {
                                    log.warn("audit failed", e);
                                }
                            }
                            public void noTransaction(Order order) {
                                try {
                                    paymentService.charge(order);
                                } catch (Exception e) {
                                    log.warn("payment failed", e);
                                }
                            }
                            @Transactional
                            public void flushes(Order order) {
                                try {
                                    orderRepository.saveAndFlush(order);
                                } catch (DataIntegrityViolationException e) {
                                    log.warn("duplicate", e);
                                }
                            }
                            @Transactional
                            public void checkedOnly(Order order) {
                                try {
                                    paymentService.charge(order);
                                } catch (IOException e) {
                                    log.warn("io", e);
                                }
                            }
                            @Transactional
                            public void ownMethod(Order order) {
                                try {
                                    place(order);
                                } catch (Exception e) {
                                    log.warn("failed", e);
                                }
                            }
                        }
                        """)
                .source("PaymentService.java", """
                        class PaymentService {
                            @Transactional
                            public void charge(Order order) throws IOException {
                                gateway.pay(order);
                            }
                        }
                        """)
                .source("AuditService.java", """
                        class AuditService {
                            @Transactional(propagation = Propagation.REQUIRES_NEW)
                            public void record(Order order) {
                                repository.save(new Audit(order));
                            }
                        }
                        """);

        // Отдельная транзакция (REQUIRES_NEW), проброс, проверяемое исключение, вызов без транзакции и вызов
        // метода своего класса общую транзакцию к откату не помечают
        assertThat(project.lines(new CaughtExceptionInTransactionRule()))
                .containsExactly("OrderService.java:10", "OrderService.java:41");
    }

    @Test
    void enumNamesMustFitIntoColumn() {
        EnumLongerThanColumnRule rule = new EnumLongerThanColumnRule();
        String status = "enum OrderStatus { NEW, WAITING_FOR_PAYMENT_CONFIRMATION, DONE }";
        String kind = "enum OrderKind { RETAIL, B2B }";
        ProjectFixture withSchema = new ProjectFixture(dir.resolve("schema"))
                .source("Order.java", ORDER)
                .source("OrderStatus.java", status)
                .source("OrderKind.java", kind)
                .write("db/migration/V1__init.sql", ORDERS_TABLE);

        // Длина из миграций важнее объявленной в @Column: previousStatus в базе вмещает 40 символов, kind - 10
        List<Violation> bySchema = withSchema.check(rule);
        assertThat(bySchema).extracting(Violation::line).containsExactly(7);
        assertThat(bySchema.get(0).confidence()).isEqualTo(Confidence.CONFIRMED);
        assertThat(bySchema.get(0).message()).contains("WAITING_FOR_PAYMENT_CONFIRMATION").contains("32").contains("20");

        // Без миграций остается только то, что сказано в коде
        ProjectFixture withoutSchema = new ProjectFixture(dir.resolve("plain"))
                .source("Order.java", ORDER)
                .source("OrderStatus.java", status)
                .source("OrderKind.java", kind);
        List<Violation> byCode = withoutSchema.check(rule);
        assertThat(byCode).extracting(Violation::line).containsExactly(10, 15);
        assertThat(byCode).extracting(Violation::confidence).containsOnly(Confidence.PROBABLE);
    }

    @Test
    void sizeConstraintMustNotExceedColumn() {
        ProjectFixture project = new ProjectFixture(dir)
                .source("Order.java", ORDER)
                .write("db/migration/V1__init.sql", ORDERS_TABLE);

        List<Violation> violations = project.check(new SizeConstraintExceedsColumnRule());

        // title: проверка пускает 200 символов, колонка вмещает 100; note в базе без длины - сверяется с @Column
        assertThat(violations).extracting(Violation::line).containsExactly(17, 22);
        assertThat(violations).extracting(Violation::confidence).containsExactly(Confidence.CONFIRMED, Confidence.PROBABLE);
    }
}
