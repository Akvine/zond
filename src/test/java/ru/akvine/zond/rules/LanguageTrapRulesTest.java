package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.exceptions.StaticInitializerFailureRule;
import ru.akvine.zond.rules.logical.CollectionViewRule;
import ru.akvine.zond.rules.logical.KeyWithoutEqualsRule;
import ru.akvine.zond.rules.logical.RecordWithArrayRule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:487 - jr:489 и jr:495: ключи без сравнения по содержимому, окна в коллекции и загрузка класса
 */
class LanguageTrapRulesTest {

    @Test
    void keyMustBeComparedByContent() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("RouteId.java", """
                class RouteId {
                    private final String region;
                    private final int code;
                }
                """);
        files.put("Session.java", """
                class Session {
                    private final String id;
                }
                """);
        files.put("Point.java", """
                class Point {
                    private final int x;
                    @Override
                    public boolean equals(Object other) {
                        return other instanceof Point point && point.x == x;
                    }
                    @Override
                    public int hashCode() {
                        return x;
                    }
                }
                """);
        files.put("Pair.java", """
                @Value
                class Pair {
                    String left;
                    String right;
                }
                """);
        files.put("Registry.java", """
                class Registry {
                    private final Map<byte[], String> byHash = new HashMap<>();
                    private final Set<String[]> rows = new HashSet<>();
                    private final Map<RouteId, String> routes = new HashMap<>();
                    private final Map<Session, User> users = new ConcurrentHashMap<>();
                    private final Map<Point, String> points = new HashMap<>();
                    private final Map<Pair, String> pairs = new HashMap<>();
                    private final Map<String, byte[]> files = new HashMap<>();
                    String route(String region) {
                        points.put(new Point(1), "a");
                        pairs.get(new Pair("a", "b"));
                        return routes.get(new RouteId(region, 1));
                    }
                    User user(Session session) {
                        return users.get(session);
                    }
                }
                """);

        List<Violation> violations = RuleTests.checkProject(new KeyWithoutEqualsRule(), files);

        // Ключ, который ищут тем же объектом, сравнение по ссылке устраивает; массив в значениях - не ключ
        assertThat(violations).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("Registry.java:2", "Registry.java:3", "RouteId.java:1");
        assertThat(violations.get(0).message()).contains("Массив в роли ключа: 'Map<byte[], String>'", "по ссылке");
        assertThat(violations.get(0).confidenceOrDefault()).isEqualTo(Confidence.CONFIRMED);
        assertThat(violations.get(2).message()).contains(
                "'RouteId' служит ключом хеш-таблицы (Map<RouteId, String> в Registry.java:4)",
                "routes.get(new RouteId(...)) в Registry.java:12", "equals и hashCode", "record");
    }

    @Test
    void viewIsNotCopy() {
        List<Violation> violations = RuleTests.check(new CollectionViewRule(), """
                class Basket {
                    private final List<Item> items;
                    private final List<Item> top;
                    private final List<Item> copy;
                    private final List<Item> stock = new ArrayList<>();
                    Basket(List<Item> items) {
                        this.items = Collections.unmodifiableList(items);
                        this.top = items.subList(0, 3);
                        this.copy = List.copyOf(items);
                    }
                    List<Item> getStock() {
                        return Collections.unmodifiableList(stock);
                    }
                    List<Item> firstInStock() {
                        return stock.subList(0, 10);
                    }
                    List<Item> page(List<Item> source) {
                        return source.subList(0, Math.min(10, source.size()));
                    }
                    int broken(List<Item> source) {
                        List<Item> head = source.subList(0, 2);
                        source.add(new Item());
                        return head.size();
                    }
                    int fine(List<Item> source) {
                        List<Item> head = new ArrayList<>(source.subList(0, 2));
                        List<Item> tail = source.subList(2, source.size());
                        int size = tail.size();
                        source.clear();
                        return head.size() + size;
                    }
                }
                """);

        // Обертка над своим полем в геттере - честное представление; окно, которым после изменения не пользуются, безопасно
        assertThat(violations).extracting(Violation::line).containsExactly(7, 8, 15, 22);
        assertThat(violations.get(0).message()).contains("представление, а не копия", "List.copyOf(...)");
        assertThat(violations.get(0).errorLevel()).isEqualTo(ErrorLevel.MINOR);
        assertThat(violations.get(1).message()).contains("'items.subList(0, 3)' сохраняется в поле");
        assertThat(violations.get(2).message()).contains("окно во внутренний список");
        assertThat(violations.get(3).message())
                .contains("'source.add(new Item())'", "subList 'head' (строка 21)", "ConcurrentModificationException");
        assertThat(violations.get(3).errorLevel()).isEqualTo(ErrorLevel.MAJOR);
    }

    @Test
    void recordComparesArrayByReference() {
        List<Violation> violations = RuleTests.check(new RecordWithArrayRule(), """
                record Packet(String id, byte[] payload) {
                }
                record Row(String... cells) {
                }
                record Safe(byte[] payload) {
                    @Override
                    public boolean equals(Object other) {
                        return other instanceof Safe safe && Arrays.equals(payload, safe.payload);
                    }
                    @Override
                    public int hashCode() {
                        return Arrays.hashCode(payload);
                    }
                }
                record Plain(String id, List<String> tags) {
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(1, 3);
        assertThat(violations.get(0).message())
                .contains("Компонент-массив 'payload' записи 'Packet'", "по ссылке", "Arrays.equals");
    }

    @Test
    void failureInStaticInitializerBreaksClass() {
        List<Violation> violations = RuleTests.check(new StaticInitializerFailureRule(), """
                class Settings {
                    private static final int LIMIT = Integer.parseInt(System.getenv("LIMIT"));
                    private static final int SAFE = Integer.parseInt(System.getProperty("limit", "10"));
                    private static final Supplier<Integer> LAZY = () -> Integer.parseInt(System.getenv("LIMIT"));
                    private static final Properties VALUES = new Properties();
                    private static final Mode MODE;
                    private final int own = Integer.parseInt(System.getenv("OWN"));
                    static {
                        try {
                            VALUES.load(Settings.class.getResourceAsStream("/settings.properties"));
                        } catch (IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                        MODE = Mode.valueOf(System.getenv("MODE"));
                    }
                    static {
                        int size;
                        try {
                            size = Integer.parseInt(System.getenv("SIZE"));
                        } catch (NumberFormatException exception) {
                            size = 10;
                        }
                    }
                    static int read() {
                        return Integer.parseInt(System.getenv("LIMIT"));
                    }
                }
                """);

        // Значение по умолчанию, лямбда, поле объекта, обычный метод и разбор внутри try с catch - не находки
        assertThat(violations).extracting(Violation::line).containsExactly(2, 12, 14);
        assertThat(violations.get(0).message())
                .contains("при загрузке класса", "NoClassDefFoundError", "Integer.getInteger");
        assertThat(violations.get(1).message()).contains("throw в статическом блоке", "ExceptionInInitializerError");
    }
}
