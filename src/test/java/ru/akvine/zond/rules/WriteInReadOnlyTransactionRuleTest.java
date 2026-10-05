package ru.akvine.zond.rules;

import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.logical.WriteInReadOnlyTransactionRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WriteInReadOnlyTransactionRuleTest {
    private final WriteInReadOnlyTransactionRule rule = new WriteInReadOnlyTransactionRule();

    @Test
    void findsWritesInReadOnlyMethods() {
        List<Violation> violations = rule.check(parse("""
                class OrderService {
                    private final OrderRepository repository;
                    private final EntityManager entityManager;
                    private final AuditService auditService;

                    @Transactional(readOnly = true)
                    public void process(Order order) {
                        repository.save(order);
                        this.repository.deleteById(1L);
                        entityManager.persist(order);
                        auditService.updateStatus(order);
                        orders.forEach(item -> repository.saveAndFlush(item));
                    }
                }
                """));

        assertThat(violations).extracting(Violation::line).containsExactly(8, 9, 10, 11, 12);
        assertThat(violations).allMatch(violation -> violation.ruleCode().equals("jr:8"));
        assertThat(violations).allMatch(violation -> violation.errorLevel() == ErrorLevel.MAJOR);
        assertThat(violations.get(0).message()).contains("'repository.save'", "'process'");
    }

    @Test
    void usesClassLevelAnnotationForPublicMethods() {
        List<Violation> violations = rule.check(parse("""
                @Transactional(readOnly = true)
                class OrderService {
                    private final OrderRepository repository;

                    public void inheritsReadOnly(Order order) {
                        repository.save(order);
                    }

                    @Transactional
                    public void overridesClass(Order order) {
                        repository.save(order);
                    }

                    void notPublic(Order order) {
                        repository.save(order);
                    }
                }
                """));

        assertThat(violations).extracting(Violation::line).containsExactly(6);
    }

    @Test
    void ignoresValidCode() {
        List<Violation> violations = rule.check(parse("""
                class OrderService {
                    private final OrderRepository repository;
                    private final Map<String, Order> cache = new HashMap<>();
                    private final List<Order> buffer = new ArrayList<>();
                    private final StringBuilder log = new StringBuilder();

                    @Transactional(readOnly = true)
                    public Order read(Long id, OrderRepository other) {
                        cache.remove("key");
                        cache.merge("key", null, null);
                        buffer.remove(0);
                        log.insert(0, "x").delete(0, 1);
                        List<Order> local = new ArrayList<>();
                        local.remove(0);
                        Order order = repository.findById(id);
                        order.updateTotal();
                        boolean saved = repository.savedBefore(id);
                        save(order);
                        return order;
                    }

                    @Transactional(readOnly = true)
                    public void shadowed(Dto repository) {
                        repository.save();
                    }

                    @Transactional
                    public void write(Order order) {
                        repository.save(order);
                    }

                    @Transactional(readOnly = false)
                    public void explicitWrite(Order order) {
                        repository.delete(order);
                    }

                    public void notTransactional(Order order) {
                        repository.save(order);
                    }

                    @Transactional(readOnly = true)
                    private void privateMethod(Order order) {
                        repository.save(order);
                    }
                }
                """));

        assertThat(violations).isEmpty();
    }

    private SourceFile parse(String code) {
        return new SourceFile(Path.of("OrderService.java"), StaticJavaParser.parse(code));
    }
}
