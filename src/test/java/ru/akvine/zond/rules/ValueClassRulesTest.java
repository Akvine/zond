package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.InternalCollectionExposureRule;
import ru.akvine.zond.rules.logical.EqualsInstanceofInHierarchyRule;
import ru.akvine.zond.rules.logical.MutableKeyClassRule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правило jr:478 и дополнения к jr:356 и jr:182: классы-значения, ключи хеш-таблиц и коллекции бинов-одиночек
 */
class ValueClassRulesTest {

    @Test
    void keyOfHashMapMustBeImmutable() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("RouteKey.java", """
                class RouteKey {
                    private String region;
                    private final String code;
                    void setRegion(String region) {
                        this.region = region;
                    }
                    @Override
                    public int hashCode() {
                        return Objects.hash(region, code);
                    }
                }
                """);
        files.put("Tag.java", """
                @Data
                class Tag {
                    private String name;
                }
                """);
        files.put("Point.java", """
                class Point {
                    private final int x;
                    private final int y;
                    @Override
                    public int hashCode() {
                        return Objects.hash(x, y);
                    }
                }
                """);
        files.put("Session.java", """
                class Session {
                    private final String id;
                    private Instant lastSeen;
                    void touch() {
                        lastSeen = Instant.now();
                    }
                    @Override
                    public int hashCode() {
                        return id.hashCode();
                    }
                }
                """);
        files.put("Handle.java", """
                class Handle {
                    private String name;
                    void setName(String name) {
                        this.name = name;
                    }
                }
                """);
        files.put("Money.java", """
                @Value
                class Money {
                    BigDecimal amount;
                    String currency;
                }
                """);
        files.put("Order.java", """
                @Entity
                @Data
                class Order {
                    @Id
                    private Long id;
                }
                """);
        files.put("Registry.java", """
                class Registry {
                    private final Map<RouteKey, String> routes = new HashMap<>();
                    private final Set<Tag> tags = new HashSet<>();
                    private final Set<Point> points = new HashSet<>();
                    private final Map<Session, User> sessions = new ConcurrentHashMap<>();
                    private final Map<Handle, String> handles = new HashMap<>();
                    private final Map<Money, Long> totals = new HashMap<>();
                    private final Map<Order, List<Item>> items = new HashMap<>();
                    private final Map<String, List<RouteKey>> byRegion = new HashMap<>();
                    private final TreeMap<Tag, Integer> sorted = new TreeMap<>();
                }
                """);

        List<Violation> violations = RuleTests.checkProject(new MutableKeyClassRule(), files);

        // Неизменяемые ключи, изменяемое поле вне hashCode, класс без своего hashCode, @Value и сущность - не находки
        assertThat(violations).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("RouteKey.java:1", "Tag.java:2");
        assertThat(violations.get(0).message()).contains(
                "'RouteKey' служит ключом хеш-таблицы (Map<RouteKey, String> в Registry.java:2)",
                "поле 'region' входит в hashCode", "в методе 'setRegion'", "record");
        assertThat(violations.get(1).message()).contains("Set<Tag> в Registry.java:3", "сеттером Lombok");
    }

    @Test
    void subclassOfValueClassBreaksEquals() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Point.java", """
                class Point {
                    private final int x;
                    @Override
                    public boolean equals(Object other) {
                        return other instanceof Point point && point.x == x;
                    }
                }
                """);
        files.put("ColorPoint.java", """
                class ColorPoint extends Point {
                    private final String color;
                }
                """);
        files.put("StrictPoint.java", """
                class StrictPoint extends Point {
                    private final int z;
                    @Override
                    public boolean equals(Object other) {
                        return other != null && getClass() == other.getClass() && super.equals(other)
                                && ((StrictPoint) other).z == z;
                    }
                }
                """);
        files.put("BaseDto.java", """
                @Data
                class BaseDto {
                    private Long id;
                }
                """);
        files.put("UserDto.java", """
                @Data
                class UserDto extends BaseDto {
                    private String name;
                }
                """);
        files.put("AdminDto.java", """
                @Data
                @EqualsAndHashCode(callSuper = true)
                class AdminDto extends BaseDto {
                    private String role;
                }
                """);
        files.put("BaseEntity.java", """
                @MappedSuperclass
                class BaseEntity {
                    @Id
                    private Long id;
                    @Override
                    public boolean equals(Object other) {
                        return other instanceof BaseEntity entity && Objects.equals(id, entity.id);
                    }
                }
                """);
        files.put("Customer.java", """
                @Entity
                class Customer extends BaseEntity {
                    private String name;
                }
                """);
        files.put("Shape.java", """
                abstract class Shape {
                    private final String name;
                    @Override
                    public boolean equals(Object other) {
                        return other instanceof Shape shape && shape.name.equals(name);
                    }
                }
                """);
        files.put("Circle.java", """
                class Circle extends Shape {
                    private final int radius;
                }
                """);

        List<Violation> violations = RuleTests.checkProject(new EqualsInstanceofInHierarchyRule(), files);

        // Сущности сравнивают по id, абстрактный предок своих объектов не имеет, callSuper = true - осознанный выбор
        assertThat(violations).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("ColorPoint.java:1", "StrictPoint.java:3", "UserDto.java:2");
        assertThat(violations.get(0).message()).contains(
                "наследует класс-значение 'Point'", "добавляет поле 'color'", "final", "композицией");
        assertThat(violations.get(0).confidenceOrDefault()).isEqualTo(Confidence.PROBABLE);
        assertThat(violations.get(1).message())
                .contains("принимает наследника", "предок.equals(наследник) вернет true", "симметричным");
        assertThat(violations.get(2).message()).contains("поля предка 'BaseDto'", "callSuper = true");
    }

    @Test
    void singletonBeanMustNotExposeItsCollections() {
        List<Violation> violations = RuleTests.check(new InternalCollectionExposureRule(), """
                @Service
                @Getter
                class HandlerRegistry {
                    private final Map<String, Handler> handlers = new HashMap<>();
                    private final List<String> names = List.of("a", "b");
                    @Getter(AccessLevel.NONE)
                    private final Set<String> hidden = new HashSet<>();
                    private final String title = "registry";
                    private final List<Rule> rules = new ArrayList<>();
                    public List<Rule> getRules() {
                        return List.copyOf(rules);
                    }
                }
                @Component
                class Limits {
                    @Getter
                    private final Set<String> blocked = new HashSet<>();
                    private final List<String> allowed = new ArrayList<>();
                    public List<String> getAllowed() {
                        return allowed;
                    }
                }
                @Getter
                class OrderDto {
                    private final List<Item> items = new ArrayList<>();
                }
                @Component
                @ConfigurationProperties("app")
                @Getter
                class AppProperties {
                    private final List<String> hosts = new ArrayList<>();
                }
                """);

        // Неизменяемая коллекция, скрытый геттер, геттер с копией, обычный DTO и класс настроек - не находки
        assertThat(violations).extracting(Violation::line).containsExactly(4, 17, 19);
        assertThat(violations.get(0).message()).contains(
                "Геттер Lombok отдает внутреннее поле 'handlers' бина 'HandlerRegistry'", "Бин один на все приложение");
        assertThat(violations.get(1).message()).contains("поле 'blocked'");
        assertThat(violations.get(2).message()).contains("Метод 'getAllowed'", "Бин один на все приложение");
    }
}
