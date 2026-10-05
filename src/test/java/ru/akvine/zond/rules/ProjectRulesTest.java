package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.logical.CircularDependencyRule;
import ru.akvine.zond.rules.security.EntityInControllerRule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:147 и jr:148: проверки, которым нужен проект целиком
 */
class ProjectRulesTest {

    @Test
    void circularDependency() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("OrderService.java", """
                @Service
                class OrderService {
                    OrderService(PaymentService payments) {}
                }
                """);
        files.put("PaymentService.java", """
                @Service
                class PaymentService {
                    @Autowired
                    private OrderService orders;
                }
                """);
        // @Lazy откладывает получение бина - цикла при создании нет
        files.put("MailService.java", """
                @Service
                class MailService {
                    MailService(@Lazy AuditService audit) {}
                }
                """);
        files.put("AuditService.java", """
                @Service
                class AuditService {
                    AuditService(MailService mail) {}
                }
                """);
        // Зависимость через интерфейс с единственной реализацией
        files.put("ReportService.java", """
                @Service
                @RequiredArgsConstructor
                class ReportService {
                    private final Exporter exporter;
                }
                """);
        files.put("CsvExporter.java", """
                @Component
                class CsvExporter implements Exporter {
                    CsvExporter(ReportService reports) {}
                }
                """);

        List<Violation> violations = RuleTests.checkProject(new CircularDependencyRule(), files);

        assertThat(violations).hasSize(2);
        assertThat(violations.get(0).file()).hasToString("CsvExporter.java");
        assertThat(violations.get(0).message()).contains("CsvExporter -> ReportService -> CsvExporter");
        assertThat(violations.get(1).file()).hasToString("OrderService.java");
        assertThat(violations.get(1).message()).contains("OrderService -> PaymentService -> OrderService");
    }

    @Test
    void entityInController() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Order.java", """
                @Entity
                class Order {
                }
                """);
        files.put("OrderController.java", """
                @RestController
                class OrderController {
                    @GetMapping
                    List<Order> list() { return null; }
                    @PostMapping
                    OrderDto create(@RequestBody Order order) { return null; }
                    @GetMapping
                    OrderDto get() { return null; }
                    Order helper() { return null; }
                }
                """);
        files.put("OrderService.java", """
                @Service
                class OrderService {
                    Order load() { return null; }
                }
                """);

        List<Violation> violations = RuleTests.checkProject(new EntityInControllerRule(), files);

        assertThat(violations).extracting(Violation::line).containsExactly(3, 6);
        assertThat(violations).allMatch(violation -> violation.file().toString().equals("OrderController.java"));
        assertThat(violations.get(0).message()).contains("возвращает сущность 'Order'");
        assertThat(violations.get(1).message()).contains("принимает сущность 'Order'");
    }
}
