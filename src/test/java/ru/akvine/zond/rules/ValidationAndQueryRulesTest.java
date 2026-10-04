package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.CheckRepositoryCollectionParameterRule;
import ru.akvine.zond.rules.exceptions.CheckSingleResultWithoutHandlingRule;
import ru.akvine.zond.rules.logical.CheckConstraintOnWrongTypeRule;
import ru.akvine.zond.rules.logical.CheckConstraintWithoutValidatedRule;
import ru.akvine.zond.rules.logical.CheckJdbcTransactionWithoutRollbackRule;
import ru.akvine.zond.rules.logical.CheckModifyingQueryMisuseRule;
import ru.akvine.zond.rules.logical.CheckNativePagingWithoutCountQueryRule;
import ru.akvine.zond.rules.logical.CheckNestedDtoWithoutValidRule;
import ru.akvine.zond.rules.logical.CheckQueryParameterMismatchRule;
import ru.akvine.zond.rules.performance.CheckFetchJoinWithPaginationRule;
import ru.akvine.zond.rules.performance.CheckSelectStarRule;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:268 - jr:278: запросы Spring Data JPA / Hibernate / JDBC и ограничения валидации
 */
class ValidationAndQueryRulesTest {

    @Test
    void modifyingQueryMisuse() {
        assertThat(RuleTests.lines(new CheckModifyingQueryMisuseRule(), """
                interface Users {
                    @Query("update User u set u.active = false")
                    void deactivate();
                    @Modifying
                    @Query("delete from User u where u.active = false")
                    User removeInactive();
                    @Modifying
                    @Query("update User u set u.active = true")
                    int activate();
                    @Query("select u from User u")
                    List<User> all();
                }
                """)).containsExactly(2, 4);
    }

    @Test
    void queryParameterMismatch() {
        List<Violation> found = RuleTests.check(new CheckQueryParameterMismatchRule(), """
                interface Users {
                    @Query("select u from User u where u.name = :name and u.age > :minAge")
                    List<User> byName(@Param("name") String name, @Param("age") int age);
                    @Query("select u from User u where u.id in :ids")
                    List<User> byIds(@Param("ids") Long ids);
                    @Query("select u from User u where u.id = :ids")
                    List<User> byIdList(@Param("ids") List<Long> ids);
                    @Query("select u from User u where u.name = ?1 and u.age = ?3")
                    List<User> positional(String name, int age);
                    @Query("select u from User u where u.id in (:ids) and u.name = :name")
                    Page<User> fine(@Param("ids") Collection<Long> ids, @Param("name") String name, Pageable pageable);
                    @Query(value = "select name::text from users where id = :id", nativeQuery = true)
                    String cast(@Param("id") Long id);
                }
                """);

        assertThat(found).extracting(Violation::line).containsExactly(2, 2, 4, 6, 8);
        assertThat(found.get(0).message()).contains("':minAge' нет среди параметров");
        assertThat(found.get(1).message()).contains("@Param(\"age\") в запросе не используется");
        assertThat(found.get(2).message()).contains("стоит в IN, а объявлен как Long");
        assertThat(found.get(3).message()).contains("коллекция, а сравнивается через '='");
        assertThat(found.get(4).message()).contains("'?3' нет");
    }

    @Test
    void fetchJoinWithPagination() {
        assertThat(RuleTests.lines(new CheckFetchJoinWithPaginationRule(), """
                interface Orders {
                    @Query("select o from Order o join fetch o.items")
                    Page<Order> withItems(Pageable pageable);
                    @Query("select o from Order o join fetch o.customer")
                    Page<Order> withCustomer(Pageable pageable);
                    @Query("select o from Order o join fetch o.items")
                    List<Order> all();
                }
                """)).containsExactly(2);
    }

    @Test
    void nativePagingWithoutCountQuery() {
        assertThat(RuleTests.lines(new CheckNativePagingWithoutCountQueryRule(), """
                interface Orders {
                    @Query(value = "select id from orders", nativeQuery = true)
                    Page<Order> page(Pageable pageable);
                    @Query(value = "select id from orders", countQuery = "select count(*) from orders", nativeQuery = true)
                    Page<Order> counted(Pageable pageable);
                    @Query(value = "select id from orders", nativeQuery = true)
                    List<Order> list();
                }
                """)).containsExactly(2);
    }

    @Test
    void selectStar() {
        assertThat(RuleTests.lines(new CheckSelectStarRule(), """
                class Sample {
                    void run() {
                        jdbc.query("select * from users", mapper);
                        jdbc.query("select u.* from users u", mapper);
                        jdbc.query("select id, name from users", mapper);
                        jdbc.queryForObject("select count(*) from users", Long.class);
                    }
                }
                """)).containsExactly(3, 4);
    }

    @Test
    void singleResultWithoutHandling() {
        assertThat(RuleTests.lines(new CheckSingleResultWithoutHandlingRule(), """
                class Sample {
                    User one(Long id) {
                        return em.createQuery("select u from User u where u.id = :id", User.class).getSingleResult();
                    }
                    Long count() {
                        return em.createQuery("select count(u) from User u", Long.class).getSingleResult();
                    }
                    User safe(Long id) {
                        try {
                            return jdbc.queryForObject("select name from users where id = ?", mapper, id);
                        } catch (EmptyResultDataAccessException exception) {
                            return null;
                        }
                    }
                    String name(Long id) {
                        return jdbc.queryForObject("select name from users where id = ?", String.class, id);
                    }
                }
                """)).containsExactly(3, 16);
    }

    @Test
    void jdbcTransactionWithoutRollback() {
        assertThat(RuleTests.lines(new CheckJdbcTransactionWithoutRollbackRule(), """
                class Sample {
                    void transfer(Connection connection) throws SQLException {
                        connection.setAutoCommit(false);
                        update(connection);
                        connection.commit();
                    }
                    void safe(Connection connection) throws SQLException {
                        connection.setAutoCommit(false);
                        try {
                            update(connection);
                            connection.commit();
                        } catch (SQLException exception) {
                            connection.rollback();
                        }
                    }
                }
                """)).containsExactly(3);
    }

    @Test
    void constraintOnWrongType() {
        List<Violation> found = RuleTests.check(new CheckConstraintOnWrongTypeRule(), """
                class Request {
                    @NotNull
                    private int count;
                    @NotNull
                    private Integer total;
                    @Size(max = 10)
                    private Long id;
                    @Size(max = 10)
                    private String name;
                    @Min(1)
                    private String code;
                    @NotBlank
                    private List<String> tags;
                    @NotEmpty
                    private List<String> items;
                    @DecimalMin("0.1")
                    private String price;
                    void run(@Min(1) int page, @NotNull boolean flag) {}
                }
                """);

        assertThat(found).extracting(Violation::line).containsExactly(2, 6, 10, 12, 18);
        assertThat(found.get(0).message()).contains("@NotNull на 'count' типа int").contains("примитив не бывает null");
        assertThat(found.get(1).message()).contains("@Size на 'id' типа Long");
        assertThat(found.get(2).message()).contains("@Min на 'code' типа String");
    }

    @Test
    void constraintWithoutValidated() {
        assertThat(RuleTests.lines(new CheckConstraintWithoutValidatedRule(), """
                @Service
                class Orders {
                    void place(@NotNull Order order) {}
                    void other(@Valid Order order) {}
                }
                @Service
                @Validated
                class Checked {
                    void place(@NotNull Order order) {}
                }
                @RestController
                class Api {
                    void create(@Valid @RequestBody Order order) {}
                }
                """)).containsExactly(3);
    }

    @Test
    void nestedDtoWithoutValid() {
        List<Violation> found = RuleTests.checkProject(new CheckNestedDtoWithoutValidRule(), Map.of(
                "Address.java", "class Address { @NotBlank private String city; }",
                "OrderRequest.java", """
                        class OrderRequest {
                            @NotNull
                            private Address address;
                            @Valid
                            private Address billing;
                            private List<Address> history;
                            private List<@Valid Address> checked;
                            private String comment;
                        }
                        """,
                // Этот класс никто не проверяет - вложенная проверка от него и не ожидается
                "Holder.java", "class Holder { private Address address; }"));

        assertThat(found).extracting(Violation::line).containsExactlyInAnyOrder(2, 6);
        assertThat(found).allSatisfy(violation -> assertThat(violation.message()).contains("в 'Address' есть ограничения"));
    }

    @Test
    void repositoryCollectionParameter() {
        assertThat(RuleTests.lines(new CheckRepositoryCollectionParameterRule(), """
                interface UserRepository extends JpaRepository<User, Long> {
                    List<User> findByIdIn(List<Long> ids);
                    List<User> findByNameIn(Collection<String> names);
                    List<User> findByRoleIn(Set<Role> roles);
                }
                interface Helper {
                    void run(List<String> values);
                }
                """)).containsExactly(2, 4);
    }
}
