package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.loaders.FileSystemConfigLoader;
import ru.akvine.zond.loaders.FileSystemTextFileLoader;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.files.DbSchema;
import ru.akvine.zond.rules.logical.EntityColumnMismatchRule;
import ru.akvine.zond.rules.logical.EntityFieldWithoutColumnRule;
import ru.akvine.zond.rules.logical.LiquibaseDuplicateChangeSetRule;
import ru.akvine.zond.rules.logical.SqlBreakingChangeRule;
import ru.akvine.zond.rules.logical.SqlDestructiveStatementRule;
import ru.akvine.zond.rules.logical.SqlTableWithoutPrimaryKeyRule;
import ru.akvine.zond.rules.logical.UnknownFieldInQueryRule;
import ru.akvine.zond.rules.logical.UnknownPropertyInQueryMethodRule;
import ru.akvine.zond.rules.performance.SqlIndexWithoutConcurrentlyRule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:331 - jr:338: сверка сущностей и запросов со схемой базы данных и проверки самих миграций
 */
class SchemaConsistencyRulesTest {
    private static final String ORDER = """
            @Entity
            @Table(name = "orders")
            class Order {
                @Id
                private Long id;
                @Column(nullable = false, length = 100)
                private String title;
                private String customerName;
                @Column(name = "state", length = 20)
                private String status;
                private BigDecimal discount;
                @ManyToOne
                private Customer customer;
                @ManyToOne
                @JoinColumn(name = "manager_ref")
                private Manager manager;
                @OneToMany(mappedBy = "order")
                private List<OrderItem> items;
                @Transient
                private String note;
                private static final int LIMIT = 10;
            }
            """;

    @TempDir
    Path dir;

    private final List<SourceFile> sources = new ArrayList<>();

    @Test
    void schemaIsBuiltFromAllMigrationsInOrder() throws IOException {
        write("db/migration/V2__change.sql", """
                alter table orders add column note text, drop column legacy;
                alter table orders rename column note to remark;
                alter table orders alter column title type varchar(200);
                alter table orders alter column remark set not null;
                alter table tags rename to labels;
                drop table if exists audit;
                """);
        write("db/migration/V10__last.sql", "alter table orders drop column remark;\n");
        write("db/migration/V1__init.sql", """
                create table orders (id bigint primary key, title varchar(100) not null, legacy int);
                create table tags (name varchar(10));
                create table audit (id bigint);
                create temporary table scratch (id int);
                create table copy as select * from orders;
                """);

        DbSchema schema = DbSchema.of(context());

        // V10 применяется после V2: порядок числовой, а не по алфавиту
        DbSchema.Table orders = schema.find("ORDERS").orElseThrow();
        assertThat(orders.columns()).extracting(DbSchema.Column::name).containsExactly("id", "title");
        assertThat(orders.find("title").orElseThrow().length()).isEqualTo(200);
        assertThat(orders.find("title").orElseThrow().notNull()).isTrue();
        assertThat(orders.hasPrimaryKey()).isTrue();
        assertThat(schema.find("labels")).isPresent();
        assertThat(schema.find("tags")).isEmpty();
        assertThat(schema.find("audit")).isEmpty();
        assertThat(schema.find("scratch")).isEmpty();
        // Состав таблицы, созданной из запроса, по миграции не виден
        assertThat(schema.find("copy").orElseThrow().isOpaque()).isTrue();
    }

    @Test
    void entityFieldsAreComparedWithColumns() throws IOException {
        write("db/migration/V1__init.sql", """
                create table orders (
                    id bigint primary key,
                    title varchar(50),
                    state varchar(20) not null,
                    customer_id bigint references customers(id),
                    manager_id bigint
                );
                alter table orders add column discount numeric(10, 2);
                """);
        source("Order.java", ORDER);
        source("Draft.java", """
                @Entity
                class Draft {
                    private String text;
                }
                """);

        // customerName -> customer_name и manager_ref в таблице нет; таблицы для Draft в миграциях нет вовсе
        assertThat(lines(new EntityFieldWithoutColumnRule())).containsExactlyInAnyOrder("Order.java:8", "Order.java:16");
        List<Violation> mismatches = new EntityColumnMismatchRule().checkContext(context());
        assertThat(mismatches).extracting(Violation::line).containsExactly(7, 7);
        assertThat(mismatches).extracting(Violation::message)
                .anySatisfy(message -> assertThat(message).contains("допускает NULL"))
                .anySatisfy(message -> assertThat(message).contains("длина 100").contains("только 50"));
    }

    @Test
    void entitiesAreNotCheckedWithoutMigrations() {
        source("Order.java", ORDER);

        assertThat(lines(new EntityFieldWithoutColumnRule())).isEmpty();
        assertThat(lines(new EntityColumnMismatchRule())).isEmpty();
    }

    @Test
    void liquibaseColumnsAreKnownToo() throws IOException {
        write("db/changelog/changelog.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                  <changeSet id="1" author="a">
                    <createTable tableName="orders">
                      <column name="id" type="bigint"><constraints primaryKey="true"/></column>
                      <column name="title" type="varchar(100)"><constraints nullable="false"/></column>
                      <column name="customer_name" type="varchar(50)"/>
                      <column name="status" type="varchar(20)"/>
                      <column name="customer_id" type="bigint"/>
                    </createTable>
                  </changeSet>
                  <changeSet id="2" author="a">
                    <renameColumn tableName="orders" oldColumnName="status" newColumnName="state"/>
                    <addColumn tableName="orders"><column name="manager_ref" type="bigint"/></addColumn>
                    <modifyDataType tableName="orders" columnName="customer_name" newDataType="varchar(80)"/>
                    <dropNotNullConstraint tableName="orders" columnName="title"/>
                  </changeSet>
                  <changeSet id="2" author="a">
                    <renameTable oldTableName="tags" newTableName="labels"/>
                  </changeSet>
                  <changeSet id="2" author="b">
                    <createTable tableName="log"><column name="text" type="text"/></createTable>
                  </changeSet>
                </databaseChangeLog>
                """);
        source("Order.java", ORDER);

        // Не хватает только discount; после dropNotNullConstraint колонка title снова допускает NULL
        assertThat(lines(new EntityFieldWithoutColumnRule())).containsExactly("Order.java:11");
        assertThat(new EntityColumnMismatchRule().checkContext(context())).extracting(Violation::message)
                .singleElement().satisfies(message -> assertThat(message).contains("допускает NULL"));

        // Повтор id у одного автора - ошибка; тот же id у другого автора допустим
        List<Violation> duplicates = new LiquibaseDuplicateChangeSetRule().checkContext(context());
        assertThat(duplicates).extracting(Violation::line).containsExactly(18);
        assertThat(duplicates.get(0).confidence()).isNull();
        assertThat(new LiquibaseDuplicateChangeSetRule().confidence()).isEqualTo(Confidence.CONFIRMED);

        assertThat(lines(new SqlBreakingChangeRule()))
                .containsExactly("changelog.xml:13", "changelog.xml:15", "changelog.xml:19");
        assertThat(lines(new SqlTableWithoutPrimaryKeyRule())).containsExactly("changelog.xml:22");
        // Снятие NOT NULL данных не удаляет
        assertThat(lines(new SqlDestructiveStatementRule())).isEmpty();
    }

    @Test
    void tableNeedsPrimaryKey() throws IOException {
        write("db/migration/V1__init.sql", """
                create table customers (id bigint primary key);
                create table events (id bigint, payload text);
                create table notes (id bigint, text varchar(10));
                create table customer_tags (
                    customer_id bigint references customers(id),
                    tag_id bigint,
                    foreign key (tag_id) references tags(id)
                );
                create table pairs (a int, b int, primary key (a, b));
                """);
        write("db/migration/V2__keys.sql", "alter table notes add constraint pk_notes primary key (id);\n");

        // Ключ у notes добавлен позже; customer_tags - таблица-связка из одних внешних ключей
        assertThat(lines(new SqlTableWithoutPrimaryKeyRule())).containsExactly("V1__init.sql:2");
    }

    @Test
    void renamesAndTypeChangesBreakRunningVersion() throws IOException {
        write("db/migration/V1__init.sql", "create table orders (id bigint primary key, total int);\n");
        write("db/migration/V2__change.sql", """
                alter table orders rename column total to amount;
                alter table orders rename to purchases;
                alter table purchases alter column amount type bigint;
                create table drafts (id bigint primary key, text varchar(10));
                alter table drafts rename column text to body;
                alter table purchases add column comment text;
                alter table purchases rename column comment to remark;
                alter table purchases rename constraint a to b;
                """);

        List<Violation> violations = new SqlBreakingChangeRule().checkContext(context());

        // Таблица и колонка, созданные в этой же миграции, никем еще не читаются
        assertThat(violations).extracting(Violation::line).containsExactly(1, 2, 3);
        assertThat(violations).extracting(Violation::confidence)
                .containsExactly(null, null, Confidence.SUSPICION);
    }

    @Test
    void indexOnExistingTableShouldBeConcurrent() throws IOException {
        write("db/migration/V1__init.sql", """
                create table orders (id bigint primary key, code varchar(10));
                create index idx_code on orders (code);
                """);
        write("db/migration/V2__index.sql", """
                create index idx_id on orders (id);
                create unique index concurrently idx_code2 on orders (code);
                """);

        List<Violation> unknownDatabase = new SqlIndexWithoutConcurrentlyRule().checkContext(context());
        assertThat(unknownDatabase).extracting(Violation::line).containsExactly(1);
        assertThat(unknownDatabase).extracting(Violation::confidence).containsOnly(Confidence.SUSPICION);

        write("src/main/resources/application.properties", "spring.datasource.url=jdbc:postgresql://db/app\n");
        assertThat(new SqlIndexWithoutConcurrentlyRule().checkContext(context()))
                .extracting(Violation::confidence).containsExactly(Confidence.PROBABLE);

        // В MySQL слова CONCURRENTLY нет
        write("src/main/resources/application.properties", "spring.datasource.url=jdbc:mysql://db/app\n");
        assertThat(new SqlIndexWithoutConcurrentlyRule().checkContext(context())).isEmpty();
    }

    @Test
    void derivedQueryMethodsMustNameExistingProperties() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", "class Base { private Long id; private Instant createdAt; }");
        files.put("Address.java", "class Address { private String city; }");
        files.put("Customer.java", """
                @Entity
                class Customer extends Base {
                    private String name;
                    private boolean active;
                    private Address address;
                    private List<Order> orders;
                    private Legacy legacy;
                }
                """);
        files.put("Order.java", "@Entity class Order extends Base { private String orderId; private String status; }");
        files.put("CustomerRepository.java", """
                interface CustomerRepository extends JpaRepository<Customer, Long> {
                    List<Customer> findByName(String name);
                    List<Customer> findByNmae(String name);
                    List<Customer> findByNameAndActiveTrueOrderByCreatedAtDesc(String name);
                    List<Customer> findByAddressCity(String city);
                    List<Customer> findByAddressStreet(String street);
                    List<Customer> findByOrdersOrderIdIn(List<String> ids);
                    long countByNameIgnoreCaseAndActiveFalse(String name);
                    Optional<Customer> findFirstByOrderByCreatedDesc();
                    List<Customer> findByLegacyCode(String code);
                    @Query("select c from Customer c where c.title = :title")
                    List<Customer> findByTitle(String title);
                    default List<Customer> findBySomething() { return List.of(); }
                    boolean existsByNameNot(String name);
                    List<Customer> getByteArrays();
                }
                """);
        files.put("ForeignRepository.java", """
                interface ForeignRepository extends JpaRepository<Remote, Long> {
                    List<Remote> findByAnything(String value);
                }
                """);

        // Тип Legacy лежит вне проекта: о его свойствах ничего не известно
        assertThat(RuleTests.checkProject(new UnknownPropertyInQueryMethodRule(), files))
                .extracting(Violation::line).containsExactly(3, 6, 9);
    }

    @Test
    void jpqlMustNameExistingFields() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Address.java", "class Address { private String city; }");
        files.put("Customer.java", """
                @Entity
                class Customer {
                    private Long key;
                    private String name;
                    private Address address;
                    private List<Order> orders;
                }
                """);
        files.put("Order.java", "@Entity class Order { private Long id; private String status; private Customer customer; }");
        files.put("CustomerRepository.java", """
                interface CustomerRepository extends JpaRepository<Customer, Long> {
                    @Query("select c from Customer c where c.name = :name and c.address.city = 'a.b'")
                    List<Customer> ok(String name);
                    @Query("select c from Customer c where c.title = :title")
                    List<Customer> wrongField(String title);
                    @Query("select c from Customer c join c.orders o where o.state = :state and c.id = 1")
                    List<Customer> wrongJoinedField(String state);
                    @Query("select o from Order o where o.customer.address.zip = :zip order by o.status")
                    List<Order> wrongNestedField(String zip);
                    @Query(value = "select c.title from customers c", nativeQuery = true)
                    List<String> nativeQuery();
                    @Query("select r from Remote r where r.anything = 1")
                    List<Object> unknownEntity();
                    @Query("update Customer c set c.nmae = :name where c.key = :key")
                    void wrongUpdate(String name, Long key);
                }
                """);

        List<Violation> violations = RuleTests.checkProject(new UnknownFieldInQueryRule(), files);

        assertThat(violations).extracting(Violation::line).containsExactly(4, 6, 8, 14);
        assertThat(violations).extracting(Violation::message)
                .anySatisfy(message -> assertThat(message).contains("'Customer.title'"))
                .anySatisfy(message -> assertThat(message).contains("'Order.state'"))
                .anySatisfy(message -> assertThat(message).contains("'Address.zip'"))
                .anySatisfy(message -> assertThat(message).contains("'Customer.nmae'"));
    }

    private ScanContext context() {
        return new ScanContext(
                dir,
                List.copyOf(sources),
                new FileSystemConfigLoader().load(dir, file -> true),
                new FileSystemTextFileLoader().load(dir, file -> true));
    }

    private void source(String name, String code) {
        sources.add(RuleTests.parse(Path.of(name), code));
    }

    private void write(String path, String content) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    // Находки в виде "файл:строка", по алфавиту
    private List<String> lines(ContextRule rule) {
        return rule.checkContext(context()).stream()
                .map(violation -> violation.file().getFileName() + ":" + violation.line())
                .sorted()
                .toList();
    }
}
