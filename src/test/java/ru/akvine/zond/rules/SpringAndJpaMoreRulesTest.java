package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:225 - jr:237: Spring и JPA
 */
class SpringAndJpaMoreRulesTest {

    @Test
    void valueOnStaticField() {
        assertThat(RuleTests.lines(new CheckValueOnStaticFieldRule(), """
                class Settings {
                    @Value("${app.name}")
                    private static String name;
                    @Value("${app.title}")
                    private String title;
                }
                """)).containsExactly(2);
    }

    @Test
    void pathVariableMismatch() {
        assertThat(RuleTests.lines(new CheckPathVariableMismatchRule(), """
                @RestController
                @RequestMapping("/users/{userId}")
                class Users {
                    @GetMapping("/orders/{id}")
                    String one(@PathVariable Long id, @PathVariable("userId") Long user, @PathVariable Long orderId) {
                        return "";
                    }
                    @GetMapping(ORDERS)
                    String constant(@PathVariable Long anything) { return ""; }
                }
                """)).containsExactly(5);
    }

    @Test
    void requestMappingWithoutMethod() {
        assertThat(RuleTests.lines(new CheckRequestMappingWithoutMethodRule(), """
                @RequestMapping("/api")
                class Api {
                    @RequestMapping("/any")
                    String any() { return ""; }
                    @RequestMapping(value = "/get", method = RequestMethod.GET)
                    String get() { return ""; }
                    @GetMapping("/list")
                    String list() { return ""; }
                }
                """)).containsExactly(3);
    }

    @Test
    void beanMethodCallOutsideConfiguration() {
        assertThat(RuleTests.lines(new CheckBeanMethodCallOutsideConfigurationRule(), """
                @Component
                class Beans {
                    @Bean
                    DataSource dataSource() { return null; }
                    @Bean
                    JdbcTemplate jdbcTemplate() {
                        return new JdbcTemplate(dataSource());
                    }
                }
                @Configuration
                class Config {
                    @Bean
                    DataSource dataSource() { return null; }
                    @Bean
                    JdbcTemplate jdbcTemplate() {
                        return new JdbcTemplate(dataSource());
                    }
                }
                """)).containsExactly(7);
    }

    @Test
    void retryableWithoutRecover() {
        assertThat(RuleTests.lines(new CheckRetryableWithoutRecoverRule(), """
                class Mailer {
                    @Retryable
                    void sendMail() {}
                    @Retryable
                    void load() {}
                }
                class Loader {
                    @Retryable
                    void load() {}
                    @Recover
                    void recover() {}
                }
                """)).containsExactly(2, 4);
    }

    @Test
    void corsAllowAll() {
        assertThat(RuleTests.lines(new CheckCorsAllowAllRule(), """
                @CrossOrigin
                class A {}
                @CrossOrigin(origins = "https://example.org")
                class B {}
                @CrossOrigin("*")
                class C {
                    void configure(CorsRegistry registry) {
                        registry.addMapping("/api").allowedOrigins("*");
                        registry.addMapping("/api").allowedOrigins("https://example.org");
                    }
                }
                """)).containsExactly(1, 5, 8);
    }

    @Test
    void endpointWithoutAuthorization() {
        assertThat(RuleTests.lines(new CheckEndpointWithoutAuthorizationRule(), """
                @RestController
                class Admin {
                    @PreAuthorize("hasRole('ADMIN')")
                    @DeleteMapping("/users/{id}")
                    void delete(Long id) {}
                    @PostMapping("/users")
                    void create() {}
                    @GetMapping("/users")
                    void list() {}
                }
                @RestController
                class Open {
                    @PostMapping("/feedback")
                    void send() {}
                }
                """)).containsExactly(6);
    }

    @Test
    void inMemoryFiltering() {
        assertThat(RuleTests.lines(new CheckInMemoryFilteringRule(), """
                class Reports {
                    void run() {
                        userRepository.findAll().stream().filter(User::isActive).toList();
                        userRepository.findAll().size();
                        userRepository.findAll().forEach(this::print);
                        userRepository.findAll(pageable).stream().filter(User::isActive);
                    }
                }
                """)).containsExactly(3, 4);
    }

    @Test
    void likeWithLeadingWildcard() {
        assertThat(RuleTests.lines(new CheckLikeWithLeadingWildcardRule(), """
                interface Users {
                    @Query("select u from User u where u.name like %:name%")
                    List<User> search(String name);
                    @Query("select u from User u where u.name like :name%")
                    List<User> prefix(String name);
                    @Query("select u from User u where lower(u.name) like concat('%', :name)")
                    List<User> suffix(String name);
                }
                """)).containsExactly(2, 6);
    }

    @Test
    void modifyingWithoutClear() {
        assertThat(RuleTests.lines(new CheckModifyingWithoutClearRule(), """
                interface Users {
                    @Modifying
                    @Query("update User u set u.active = false")
                    void deactivate();
                    @Modifying(clearAutomatically = true)
                    @Query("update User u set u.active = true")
                    void activate();
                }
                """)).containsExactly(2);
    }

    @Test
    void flushInLoop() {
        assertThat(RuleTests.lines(new CheckFlushInLoopRule(), """
                class Importer {
                    void run(List<Order> orders) {
                        for (Order order : orders) {
                            orderRepository.saveAndFlush(order);
                        }
                        int i = 0;
                        for (Order order : orders) {
                            entityManager.persist(order);
                            if (++i % 50 == 0) {
                                entityManager.flush();
                            }
                        }
                        entityManager.flush();
                    }
                }
                """)).containsExactly(4);
    }

    @Test
    void referenceOutsideTransaction() {
        assertThat(RuleTests.lines(new CheckReferenceOutsideTransactionRule(), """
                class Orders {
                    String title(Long id) {
                        Order order = orderRepository.getReferenceById(id);
                        return order.getTitle();
                    }
                    Long id(Long id) {
                        Order order = orderRepository.getReferenceById(id);
                        return order.getId();
                    }
                    @Transactional
                    String inTransaction(Long id) {
                        Order order = orderRepository.getReferenceById(id);
                        return order.getTitle();
                    }
                }
                """)).containsExactly(4);
    }
}
