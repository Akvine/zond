package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.config.RuleSettings;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.DuplicateCodeRule;
import ru.akvine.zond.rules.codesmell.LayerViolationRule;
import ru.akvine.zond.rules.codesmell.PackageCycleRule;
import ru.akvine.zond.rules.logical.DuplicateEndpointRule;
import ru.akvine.zond.rules.logical.LazyAccessOutsideTransactionRule;
import ru.akvine.zond.rules.logical.MissingEnableAnnotationRule;
import ru.akvine.zond.rules.logical.MultipleWritesWithoutTransactionRule;
import ru.akvine.zond.rules.logical.PrototypeInSingletonRule;
import ru.akvine.zond.rules.performance.RequiresNewInLoopRule;
import ru.akvine.zond.rules.security.EntityAsRequestBodyRule;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила, которым нужен проект целиком: транзакции через вызовы, пакеты, слои, обработчики, копии кода
 */
class ProjectStructureRulesTest {
    private static final String LONG_BODY = """
                void %s(List<String> lines) {
                    int count = 0;
                    for (String line : lines) {
                        // %s
                        if (line.isBlank()) {
                            continue;
                        }
                        count++;
                    }
                    report(count);
                    report(lines.size());
                }
            """;

    @Test
    void multipleWritesWithoutTransaction() {
        List<Violation> found = RuleTests.check(new MultipleWritesWithoutTransactionRule(), """
                @Service
                class Orders {
                    void place(Order order) {
                        orderRepository.save(order);
                        writeAudit(order);
                    }
                    private void writeAudit(Order order) {
                        auditRepository.save(order);
                    }
                    @Transactional
                    void placeSafely(Order order) {
                        orderRepository.save(order);
                        auditRepository.save(order);
                    }
                    void single(Order order) {
                        orderRepository.save(order);
                    }
                }
                """);

        // Вторая запись спрятана во вспомогательном методе того же класса
        assertThat(found).singleElement().satisfies(violation -> {
            assertThat(violation.line()).isEqualTo(3);
            assertThat(violation.message()).contains("orderRepository.save, auditRepository.save");
        });
    }

    @Test
    void requiresNewInLoop() {
        assertThat(RuleTests.lines(new RequiresNewInLoopRule(), """
                class Batch {
                    void run(List<Order> orders) {
                        for (Order order : orders) {
                            process(order);
                        }
                        process(orders.get(0));
                    }
                    @Transactional(propagation = Propagation.REQUIRES_NEW)
                    public void process(Order order) {}
                }
                """)).containsExactly(4);
    }

    @Test
    void lazyAccessInCalledMethod() {
        assertThat(RuleTests.lines(new LazyAccessOutsideTransactionRule(), """
                class Reports {
                    void print(Long id) {
                        Order order = orderRepository.findById(id).orElseThrow();
                        render(order);
                    }
                    void render(Order order) {
                        order.getItems().size();
                    }
                }
                """)).containsExactly(4);
    }

    @Test
    void packageCycle() {
        List<Violation> found = RuleTests.checkProject(new PackageCycleRule(), Map.of(
                "a/Foo.java", "package app.a;\nimport app.b.Bar;\nclass Foo {}",
                "b/Bar.java", "package app.b;\nimport app.a.Foo;\nclass Bar {}",
                "c/Baz.java", "package app.c;\nimport app.a.Foo;\nclass Baz {}",
                // Пакет и его подпакет зависят друг от друга - это не считается
                "a/sub/Deep.java", "package app.a.sub;\nimport app.a.Foo;\nclass Deep {}",
                "a/Top.java", "package app.a;\nimport app.a.sub.Deep;\nclass Top {}"));

        assertThat(found).singleElement()
                .satisfies(violation -> assertThat(violation.message()).contains("'app.a' и 'app.b'"));
    }

    @Test
    void layerViolation() {
        List<Violation> found = RuleTests.checkProject(new LayerViolationRule(), Map.of(
                "OrderController.java", """
                        @RestController
                        class OrderController {
                            private final OrderService service;
                            OrderController(OrderService service) { this.service = service; }
                        }
                        """,
                "OrderService.java", """
                        @Service
                        class OrderService {
                            private final OrderController controller;
                            OrderService(OrderController controller) { this.controller = controller; }
                        }
                        """));

        assertThat(found).singleElement().satisfies(violation -> assertThat(violation.message())
                .contains("'OrderService' (сервис) зависит от 'OrderController' (контроллер)"));
    }

    @Test
    void duplicateEndpoint() {
        assertThat(RuleTests.lines(new DuplicateEndpointRule(), """
                @RestController
                @RequestMapping("/users")
                class Users {
                    @GetMapping("/{id}")
                    String one(Long id) { return ""; }
                    @GetMapping("{userId}")
                    String other(Long userId) { return ""; }
                    @PostMapping("/{id}")
                    String update(Long id) { return ""; }
                    @GetMapping(value = "/{id}", params = "full")
                    String full(Long id) { return ""; }
                }
                """)).containsExactly(6);
    }

    @Test
    void missingEnableAnnotation() {
        List<Violation> found = RuleTests.checkProject(new MissingEnableAnnotationRule(), Map.of(
                "App.java", "@SpringBootApplication\nclass App {}",
                "Config.java", "@Configuration\n@EnableScheduling\nclass Config {}",
                "Jobs.java", """
                        class Jobs {
                            @Async
                            void first() {}
                            @Async
                            void second() {}
                            @Scheduled(fixedDelay = 1000)
                            void third() {}
                        }
                        """));

        // @EnableScheduling есть, @EnableAsync нет; о недостающей аннотации сообщается один раз
        assertThat(found).singleElement().satisfies(violation -> {
            assertThat(violation.line()).isEqualTo(2);
            assertThat(violation.message()).contains("@Async").contains("@EnableAsync");
        });
    }

    @Test
    void missingEnableAnnotationNeedsConfigurationInScope() {
        // Настроек приложения среди проверенных файлов нет - судить об отсутствии @Enable... нельзя
        assertThat(RuleTests.check(new MissingEnableAnnotationRule(),
                "class Jobs { @Async void first() {} }")).isEmpty();
    }

    @Test
    void prototypeInSingleton() {
        List<Violation> found = RuleTests.checkProject(new PrototypeInSingletonRule(), Map.of(
                "Report.java", "@Component\n@Scope(\"prototype\")\nclass Report {}",
                "Printer.java", """
                        @Service
                        class Printer {
                            private final Report report;
                            Printer(Report report) { this.report = report; }
                        }
                        """,
                "LazyPrinter.java", """
                        @Service
                        class LazyPrinter {
                            private final ObjectProvider<Report> reports;
                            LazyPrinter(ObjectProvider<Report> reports) { this.reports = reports; }
                        }
                        """));

        assertThat(found).singleElement()
                .satisfies(violation -> assertThat(violation.message()).contains("singleton 'Printer'"));
    }

    @Test
    void entityAsRequestBody() {
        List<Violation> found = RuleTests.checkProject(new EntityAsRequestBodyRule(), Map.of(
                "User.java", "@Entity\nclass User { @Id Long id; }",
                "Users.java", """
                        @RestController
                        class Users {
                            @PostMapping("/users")
                            void create(@RequestBody User user) {}
                            @PostMapping("/users/dto")
                            void createFromDto(@RequestBody UserDto dto) {}
                        }
                        """));

        assertThat(found).extracting(Violation::line).containsExactly(4);
    }

    @Test
    void duplicateCode() {
        Map<String, String> files = Map.of(
                "First.java", "class First {\n" + LONG_BODY.formatted("count", "первый вариант") + "}",
                "Second.java", "class Second {\n" + LONG_BODY.formatted("total", "копия с другим комментарием") + "}",
                "Short.java", "class Short { void a() { run(); } void b() { run(); } }");

        List<Violation> found = RuleTests.checkProject(new DuplicateCodeRule(), files);
        assertThat(found).singleElement()
                .satisfies(violation -> assertThat(violation.message()).contains("дословно повторяет"));

        // Порог длины настраивается: при большем значении эти тела копиями не считаются
        DuplicateCodeRule strict = new DuplicateCodeRule();
        strict.setSettings(RuleSettings.of(Map.of("jr-256.min-lines", "50")));
        assertThat(RuleTests.checkProject(strict, files)).isEmpty();
    }
}
