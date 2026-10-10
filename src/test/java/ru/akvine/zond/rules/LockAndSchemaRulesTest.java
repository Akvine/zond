package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.concurrency.LockReleasedBeforeCommitRule;
import ru.akvine.zond.rules.files.DbSchema;
import ru.akvine.zond.rules.logical.EntityColumnTypeMismatchRule;
import ru.akvine.zond.rules.performance.QueryWithoutIndexRule;
import ru.akvine.zond.rules.support.DerivedQueries;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:390 - jr:392: блокировка, отпущенная до коммита, запрос по колонке без индекса и тип поля,
 * не подходящий к типу колонки
 */
class LockAndSchemaRulesTest {
    private static final String CLIENT = """
            @Entity
            @Table(name = "clients")
            class Client {
                @Id
                private Long id;
                private String email;
                private String phone;
                private String lastName;
                private String firstName;
                private boolean active;
                @ManyToOne
                private Bank bank;
                private LocalDate birthDate;
                private String comment;
            }
            """;
    private static final String CLIENT_REPOSITORY = """
            interface ClientRepository extends JpaRepository<Client, Long> {
                Optional<Client> findByEmail(String email);
                List<Client> findByPhone(String phone);
                List<Client> findByLastNameAndFirstName(String lastName, String firstName);
                List<Client> findByFirstName(String firstName);
                List<Client> findByActive(boolean active);
                List<Client> findByBankId(Long bankId);
                List<Client> findByBirthDateBetween(LocalDate from, LocalDate to);
                List<Client> findByCommentContaining(String part);
                List<Client> findByEmailIgnoreCase(String email);
                Optional<Client> findById(Long id);
                @Query("select c from Client c where c.phone = :phone")
                List<Client> customByPhone(String phone);
                List<Client> findByPhoneOrEmail(String phone, String email);
                List<Client> findByPhoneAndActiveTrue(String phone);
                List<Client> findByAddressCity(String city);
                boolean existsByPhone(String phone);
            }
            """;
    private static final String CLIENTS_TABLE = """
            create table clients (
                id bigint primary key, email varchar(100), phone varchar(20), last_name varchar(50),
                first_name varchar(50), active boolean, bank_id bigint references banks(id), birth_date date,
                comment text
            );
            create unique index ux_clients_email on clients (email);
            create index ix_clients_name on clients (last_name, first_name);
            """;

    @TempDir
    Path dir;

    @Test
    void lockMustOutliveTransaction() {
        List<Integer> lines = RuleTests.lines(new LockReleasedBeforeCommitRule(), """
                class TeamService {
                    @Transactional
                    public void delete(Team team) {
                        Lock lock = locks.get(team.getId());
                        lock.lock();
                        try {
                            teamRepository.delete(team);
                        } finally {
                            lock.unlock();
                        }
                    }
                    @Transactional
                    public synchronized void rename(Team team) {
                        teamRepository.save(team);
                    }
                    @Transactional
                    public void counter(Team team) {
                        synchronized (this) {
                            teamRepository.save(team);
                        }
                        synchronized (cache) {
                            cache.put(team.getId(), team);
                        }
                    }
                    public void outside(Team team) {
                        lock.lock();
                        try {
                            transactionalService.delete(team);
                        } finally {
                            lock.unlock();
                        }
                    }
                    @Transactional(readOnly = true)
                    public Team read(Long id) {
                        lock.lock();
                        try {
                            return teamRepository.findById(id).orElseThrow();
                        } finally {
                            lock.unlock();
                        }
                    }
                    @Transactional
                    public void afterCommitRelease(Team team) {
                        lock.lock();
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void afterCompletion(int status) {
                                lock.unlock();
                            }
                        });
                        teamRepository.save(team);
                    }
                    @Transactional
                    public void rowLock(Team team) {
                        entityManager.lock(team, LockModeType.PESSIMISTIC_WRITE);
                        teamRepository.save(team);
                    }
                    @Transactional
                    public void tryIt(Team team) {
                        if (redisLock.tryLock()) {
                            try {
                                teamRepository.save(team);
                            } finally {
                                redisLock.unlock();
                            }
                        }
                    }
                    @Transactional(propagation = Propagation.NOT_SUPPORTED)
                    public void withoutTransaction(Team team) {
                        lock.lock();
                        try {
                            transactionalService.delete(team);
                        } finally {
                            lock.unlock();
                        }
                    }
                }
                """);

        // Блокировка снаружи транзакции, транзакция только на чтение, unlock после коммита, блокировка строки
        // в базе и блок synchronized вокруг карты в памяти - правильные либо посторонние случаи
        assertThat(lines).containsExactly(5, 13, 18, 60);
    }

    @Test
    void transactionOnClassCountsToo() {
        List<Integer> lines = RuleTests.lines(new LockReleasedBeforeCommitRule(), """
                @Service
                @Transactional
                class AccountService {
                    public void transfer(Account from, Account to) {
                        lock.lock();
                        try {
                            accountRepository.save(from);
                        } finally {
                            lock.unlock();
                        }
                    }
                    private void helper() {
                        lock.lock();
                        lock.unlock();
                    }
                }
                """);

        // На закрытый метод @Transactional класса не действует: транзакции у него своей нет
        assertThat(lines).containsExactly(5);
    }

    @Test
    void schemaRemembersIndexesAndTheirColumnOrder() {
        ProjectFixture project = new ProjectFixture(dir).write("db/migration/V1__init.sql", """
                create table orders (
                    id bigint primary key, number varchar(20), customer_id bigint, status varchar(10),
                    created_at timestamp, region varchar(10), manager varchar(50), note text,
                    index ix_region (region)
                );
                create index ix_orders_customer on orders (customer_id, status);
                create index concurrently if not exists ix_orders_created on orders using btree (created_at desc);
                create unique index ux_orders_number on orders (number);
                create index ix_manager on orders (manager);
                drop index ix_manager;
                alter table orders add index ix_note (note);
                alter table orders rename column region to area;
                """);

        DbSchema.Table orders = DbSchema.of(project.context()).find("orders").orElseThrow();

        assertThat(orders.hasIndexOn(List.of("id"))).isTrue();
        assertThat(orders.hasIndexOn(List.of("number"))).isTrue();
        assertThat(orders.hasIndexOn(List.of("customerId"))).isTrue();
        assertThat(orders.hasIndexOn(List.of("createdAt"))).isTrue();
        // Вторая колонка составного индекса сама по себе поиску не помогает, а вместе с первой - да
        assertThat(orders.hasIndexOn(List.of("status"))).isFalse();
        assertThat(orders.hasIndexOn(List.of("status", "customer_id"))).isTrue();
        // Удаленного индекса нет; индекс переезжает вместе с переименованной колонкой
        assertThat(orders.hasIndexOn(List.of("manager"))).isFalse();
        assertThat(orders.hasIndexOn(List.of("area"))).isTrue();
        assertThat(orders.hasIndexOn(List.of("region"))).isFalse();
        assertThat(orders.hasIndexOn(List.of("note"))).isTrue();
        // Обычный индекс уникальности не дает
        assertThat(orders.isUnique(List.of("customerId", "status"))).isFalse();
        assertThat(orders.isUnique(List.of("number"))).isTrue();
    }

    @Test
    void derivedQueryNameIsParsedIntoConditions() {
        DerivedQueries.Query query = DerivedQueries.parse("findFirstByEmailIgnoreCaseAndAgeGreaterThanOrderByNameDesc")
                .orElseThrow();
        assertThat(query.action()).isEqualTo("find");
        assertThat(query.alternatives()).isFalse();
        assertThat(query.parts()).containsExactly(
                new DerivedQueries.Part("email", DerivedQueries.Operator.EQUALS, true),
                new DerivedQueries.Part("age", DerivedQueries.Operator.RANGE, false));
        assertThat(query.equalityProperties()).isEmpty();

        assertThat(DerivedQueries.parse("existsByCodeAndTenantId").orElseThrow().equalityProperties())
                .contains(List.of("code", "tenantId"));
        assertThat(DerivedQueries.parse("existsByCode").orElseThrow().isExistenceCheck()).isTrue();
        assertThat(DerivedQueries.parse("findByStatusNotIn").orElseThrow().parts())
                .containsExactly(new DerivedQueries.Part("status", DerivedQueries.Operator.OTHER, false));
        assertThat(DerivedQueries.parse("findByStatusIn").orElseThrow().parts())
                .containsExactly(new DerivedQueries.Part("status", DerivedQueries.Operator.IN, false));
        assertThat(DerivedQueries.parse("findByNameStartingWith").orElseThrow().parts())
                .containsExactly(new DerivedQueries.Part("name", DerivedQueries.Operator.PREFIX, false));
        assertThat(DerivedQueries.parse("findByPhoneOrEmail").orElseThrow().alternatives()).isTrue();
        // Не запрос по имени: условий после By нет либо имя построено иначе
        assertThat(DerivedQueries.parse("findAll")).isEmpty();
        assertThat(DerivedQueries.parse("findAllByOrderByNameAsc")).isEmpty();
        assertThat(DerivedQueries.parse("save")).isEmpty();
    }

    @Test
    void queryByColumnNeedsIndex() {
        QueryWithoutIndexRule rule = new QueryWithoutIndexRule();
        ProjectFixture project = new ProjectFixture(dir.resolve("plain"))
                .source("Client.java", CLIENT)
                .source("ClientRepository.java", CLIENT_REPOSITORY)
                .write("db/migration/V1__init.sql", CLIENTS_TABLE);

        // email и (last_name, first_name) под индексом; логический флаг, поиск по части строки, без учета
        // регистра, по ключу, запрос из @Query, условия через Or и вложенное свойство правило не оценивает
        List<Violation> violations = project.check(rule);
        assertThat(violations).extracting(Violation::line).containsExactly(3, 5, 7, 8, 15, 17);
        assertThat(violations.get(0).message()).contains("findByPhone").contains("'phone'").contains("'clients'");
        assertThat(rule.confidence()).isEqualTo(Confidence.SUSPICION);

        // Индекс появился - находки по этой колонке исчезли
        ProjectFixture indexed = new ProjectFixture(dir.resolve("indexed"))
                .source("Client.java", CLIENT)
                .source("ClientRepository.java", CLIENT_REPOSITORY)
                .write("db/migration/V1__init.sql", CLIENTS_TABLE)
                .write("db/migration/V2__index.sql", "create index ix_clients_phone on clients (phone);\n");
        assertThat(indexed.check(rule)).extracting(Violation::line).containsExactly(5, 7, 8);

        // Без миграций об индексах ничего не известно
        ProjectFixture withoutSchema = new ProjectFixture(dir.resolve("none"))
                .source("Client.java", CLIENT)
                .source("ClientRepository.java", CLIENT_REPOSITORY);
        assertThat(withoutSchema.check(rule)).isEmpty();
    }

    @Test
    void fieldTypeMustSuitColumnType() {
        ProjectFixture project = new ProjectFixture(dir)
                .source("Payment.java", """
                        @Entity
                        @Table(name = "payments")
                        class Payment {
                            @Id
                            private Long id;
                            private Long amount;
                            private String currency;
                            private LocalDateTime createdAt;
                            private boolean paid;
                            private Boolean refunded;
                            private Boolean archived;
                            @Enumerated(EnumType.STRING)
                            private PaymentStatus status;
                            @Enumerated
                            private PaymentStatus previous;
                            private UUID externalId;
                            private byte[] receipt;
                            @Convert(converter = MoneyConverter.class)
                            private Money total;
                            private Money fee;
                            @ManyToOne
                            private Client client;
                            @Column(columnDefinition = "jsonb")
                            private String payload;
                            private String comment;
                            private String code;
                            private BigDecimal rate;
                            private Instant paidAt;
                            private Weight weight;
                        }
                        """)
                .source("PaymentStatus.java", "enum PaymentStatus { NEW, DONE }")
                .source("WeightConverter.java", """
                        @Converter(autoApply = true)
                        class WeightConverter implements AttributeConverter<Weight, String> {
                        }
                        """)
                .write("db/migration/V1__init.sql", """
                        create table payments (
                            id bigint primary key,
                            amount varchar(20),
                            currency varchar(3),
                            created_at bigint,
                            paid number(1),
                            refunded char(1),
                            archived varchar(10),
                            status integer,
                            previous varchar(20),
                            external_id varchar(36),
                            receipt text,
                            total numeric(19, 2),
                            fee varchar(20),
                            client_id bigint,
                            payload jsonb,
                            comment jsonb,
                            code integer,
                            rate numeric(10, 4),
                            paid_at timestamp with time zone,
                            weight varchar(10)
                        );
                        """);

        List<Violation> violations = project.check(new EntityColumnTypeMismatchRule());

        // Логическое значение в числе и в одном символе, UUID в строке, поле с преобразователем, связь, колонка
        // со своим определением и тип базы, о котором судить нельзя (jsonb), - не расхождение; перечисление,
        // хранимое номером, показывает другое правило
        assertThat(violations).extracting(Violation::line).containsExactly(6, 8, 11, 13, 17, 26);
        assertThat(violations.get(0).message())
                .contains("'Payment.amount'").contains("число (Long)").contains("строка (varchar(20))");
        assertThat(violations.get(3).message()).contains("имя константы перечисления").contains("число (integer)");
    }
}
