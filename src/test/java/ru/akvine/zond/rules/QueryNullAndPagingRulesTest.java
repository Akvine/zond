package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.logical.NullableColumnInQueryRule;
import ru.akvine.zond.rules.performance.OffsetPaginationRule;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:474 и jr:475: постраничная выдача через смещение и NULL в условиях запросов
 */
class QueryNullAndPagingRulesTest {
    private static final String PRODUCT = """
            @Entity
            class Product extends BaseEntity {
                @Column(nullable = false)
                private String name;
                private String status;
                private BigDecimal price;
                private Long categoryId;
                private int stock;
                @ManyToOne(optional = false)
                private Brand brand;
            }
            """;
    private static final String BASE_ENTITY = """
            @MappedSuperclass
            class BaseEntity {
                @Id
                private Long id;
                private String comment;
            }
            """;
    private static final String BRAND = """
            @Entity
            class Brand {
                @Id
                private Long id;
                private String country;
            }
            """;
    private static final String REPOSITORY = """
            interface ProductRepository extends JpaRepository<Product, Long> {
                List<Product> findByStatusNot(String status);
                List<Product> findByCategoryIdNotInAndNameNot(List<Long> ids, String name);
                List<Product> findByStatusNotOrStatusIsNull(String status);
                List<Product> findByStockNot(int stock);
                List<Product> findByCommentIsNotNull();
                @Query("select p from Product p where p.status <> :status and p.name <> :name")
                List<Product> active(String status, String name);
                @Query("select p from Product p where p.status <> :status or p.status is null")
                List<Product> activeOrEmpty(String status);
                @Query("select p from Product p join p.brand b where :country <> b.country and p.comment not like :mask")
                List<Product> foreign(String country, String mask);
                @Query("select p from Product p where p.id not in (select o.categoryId from Product o)")
                List<Product> notReferenced();
                @Query("select p from Product p where p.id not in (select o.categoryId from Product o where o.categoryId is not null)")
                List<Product> notReferencedSafely();
                @Query("select sum(p.price) from Product p where p.status = :status")
                long total(String status);
                @Query("select max(p.price) from Product p")
                BigDecimal maxPrice();
                @Query("select coalesce(sum(p.price), 0) from Product p")
                BigDecimal safeTotal();
                @Query("select sum(p.price) from Product p group by p.status")
                List<BigDecimal> totals();
                @Query("select min(p.price) from Product p")
                Optional<BigDecimal> minPrice();
                @Query(value = "select * from product where status = null", nativeQuery = true)
                List<Product> broken();
                @Modifying
                @Query(value = "update product set status = null where category_id <> NULL", nativeQuery = true)
                void reset();
                @Modifying
                @Query("update Product p set p.status = null where p.id = :id")
                void clear(Long id);
            }
            """;

    @TempDir
    Path dir;

    @Test
    void nullInColumnSilentlyDropsRows() {
        NullableColumnInQueryRule rule = new NullableColumnInQueryRule();
        List<Violation> violations = new ProjectFixture(dir)
                .source("Product.java", PRODUCT)
                .source("BaseEntity.java", BASE_ENTITY)
                .source("Brand.java", BRAND)
                .source("ProductRepository.java", REPOSITORY)
                .check(rule).stream()
                .sorted(Comparator.comparingInt(Violation::line))
                .toList();

        // Колонка NOT NULL, примитив, условие с IS NULL, COALESCE, GROUP BY, Optional и присваивание в SET - не находки
        assertThat(violations).extracting(Violation::line).containsExactly(2, 3, 8, 12, 12, 14, 18, 20, 27, 30);
        assertThat(violations.get(0).message())
                .contains("'findByStatusNot'", "свойству 'status'", "OrStatusIsNull", "нет ни nullable = false");
        assertThat(violations.get(0).confidenceOrDefault()).isEqualTo(Confidence.PROBABLE);
        assertThat(violations.get(1).message()).contains("NotIn по свойству 'categoryId'");
        assertThat(violations.get(2).message()).contains("'p.status <> ...'", "OR p.status IS NULL");
        assertThat(violations).extracting(Violation::message)
                .anyMatch(message -> message.contains("'b.country <> ...'"))
                .anyMatch(message -> message.contains("'p.comment NOT LIKE ...'"));
        assertThat(violations.get(5).message()).contains("подзапрос в NOT IN выбирает 'o.categoryId'", "NOT EXISTS");
        assertThat(violations.get(6).message()).contains("SUM по пустой выборке", "примитивом long", "COALESCE");
        assertThat(violations.get(6).errorLevel()).isEqualTo(ErrorLevel.MAJOR);
        assertThat(violations.get(7).message()).contains("MAX по пустой выборке", "вернет null");
        assertThat(violations.get(7).errorLevel()).isEqualTo(ErrorLevel.MINOR);
        assertThat(violations.get(8).message()).contains("'status = null'", "IS NULL");
        assertThat(violations.get(8).confidenceOrDefault()).isEqualTo(Confidence.CONFIRMED);
        assertThat(violations.get(9).message()).contains("'category_id <> NULL'");
    }

    @Test
    void migrationsDecideWhetherColumnIsNullable() {
        NullableColumnInQueryRule rule = new NullableColumnInQueryRule();
        List<Violation> violations = new ProjectFixture(dir)
                .source("Product.java", PRODUCT)
                .source("BaseEntity.java", BASE_ENTITY)
                .source("Brand.java", BRAND)
                .source("ProductRepository.java", """
                        interface ProductRepository extends JpaRepository<Product, Long> {
                            List<Product> findByStatusNot(String status);
                            List<Product> findByCategoryIdNot(Long categoryId);
                        }
                        """)
                .write("db/migration/V1__init.sql", """
                        create table product (
                            id bigint primary key,
                            name varchar(255) not null,
                            status varchar(32) not null,
                            price numeric(19, 2),
                            category_id bigint,
                            stock int not null,
                            brand_id bigint not null,
                            comment text
                        );
                        """)
                .check(rule);

        // В сущности status ничем не помечен, но в миграции колонка NOT NULL; category_id допускает NULL
        assertThat(violations).extracting(Violation::line).containsExactly(3);
        assertThat(violations.get(0).message()).contains("в миграциях колонка объявлена без NOT NULL");
        assertThat(violations.get(0).confidenceOrDefault()).isEqualTo(Confidence.CONFIRMED);
    }

    @Test
    void offsetPaginationIsOnlyAdvice() {
        OffsetPaginationRule rule = new OffsetPaginationRule();
        List<Violation> violations = RuleTests.check(rule, """
                @RestController
                class ProductController {
                    @GetMapping("/products")
                    Page<Product> list(Pageable pageable) {
                        return service.list(pageable);
                    }
                    @GetMapping("/top")
                    List<Product> top(@RequestParam int page, @RequestParam int size) {
                        Pageable first = PageRequest.of(0, size);
                        return repository.findAll(PageRequest.of(page, size, Sort.by("id"))).getContent();
                    }
                    List<Product> raw(int size, int offset) {
                        return jdbcTemplate.query("select * from product order by id limit ? offset ?", mapper, size, offset);
                    }
                    List<Product> criteria(int offset, int size) {
                        return entityManager.createQuery(query).setFirstResult(offset).setMaxResults(size).getResultList();
                    }
                    List<Product> dsl(long offset) {
                        return queryFactory.selectFrom(product).limit(20).offset(offset).fetch();
                    }
                    void all() {
                        int page = 0;
                        Page<Product> current;
                        do {
                            current = repository.findAll(PageRequest.of(page++, 100));
                        } while (current.hasNext());
                    }
                    List<Product> keyset(Long lastId, int size) {
                        return jdbcTemplate.query("select * from product where id > ? order by id limit ?", mapper, lastId, size);
                    }
                    long position(ConsumerRecord<String, String> record, Buffer buffer) {
                        buffer.offset(10);
                        return record.offset();
                    }
                }
                class ProductService {
                    Page<Product> list(Pageable pageable) {
                        return repository.findAll(pageable);
                    }
                }
                """);

        // Первая страница, keyset-запрос, обход в цикле (о нем сообщает jr:431) и Pageable вне обработчика - не находки
        assertThat(violations).extracting(Violation::line).containsExactly(4, 10, 13, 16, 19);
        assertThat(violations.get(0).message())
                .contains("параметр 'pageable' обработчика запроса", "keyset-пагинация", "WHERE id > :lastId");
        assertThat(violations.get(1).message()).contains("PageRequest.of(page, size, Sort.by(");
        assertThat(violations.get(2).message()).contains("OFFSET в запросе");
        // Совет, а не ошибка: уместен только на больших таблицах, чего по коду не видно
        assertThat(rule.errorLevel()).isEqualTo(ErrorLevel.INFO);
        assertThat(rule.confidence()).isEqualTo(Confidence.SUSPICION);
    }
}
