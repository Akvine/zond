package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.loaders.FileSystemConfigLoader;
import ru.akvine.zond.loaders.FileSystemTextFileLoader;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ConfigProperty;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.SerializableWithoutSerialVersionUidRule;
import ru.akvine.zond.rules.logical.EqualsInstanceofInHierarchyRule;
import ru.akvine.zond.rules.logical.ListRemoveByIndexRule;
import ru.akvine.zond.rules.logical.MapperMissesFieldRule;
import ru.akvine.zond.rules.logical.MutableMapKeyRule;
import ru.akvine.zond.rules.logical.NonSerializableFieldRule;
import ru.akvine.zond.rules.logical.OddCheckWithModuloRule;
import ru.akvine.zond.rules.logical.OverridableCallInConstructorRule;
import ru.akvine.zond.rules.logical.StringBuilderCharConstructorRule;
import ru.akvine.zond.rules.resources.GracefulShutdownMissingRule;
import ru.akvine.zond.rules.security.InsecureHttpUrlRule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:349 - jr:359: ошибки, которые язык позволяет написать незаметно, и настройки выкладки
 */
class LanguagePitfallRulesTest {
    @TempDir
    Path dir;

    @Test
    void oddCheckFailsForNegativeNumbers() {
        assertThat(RuleTests.lines(new OddCheckWithModuloRule(), """
                class Sample {
                    boolean check(int value, List<String> items, int[] array) {
                        boolean odd = value % 2 == 1;
                        boolean even = 1 != (value % 2);
                        boolean correct = value % 2 != 0;
                        boolean bySize = items.size() % 2 == 1;
                        boolean byLength = array.length % 2 == 1;
                        boolean byAbs = Math.abs(value) % 2 == 1;
                        boolean third = value % 3 == 1;
                        for (int i = 0; i < 10; i++) {
                            if (i % 2 == 1) {
                                return true;
                            }
                        }
                        for (int i = 10; i > -10; i--) {
                            if (i % 2 == 1) {
                                return true;
                            }
                        }
                        return odd;
                    }
                }
                """)).containsExactly(3, 4, 16);
    }

    @Test
    void removeOnListOfIntegersTakesIndex() {
        assertThat(RuleTests.lines(new ListRemoveByIndexRule(), """
                class Sample {
                    private List<Integer> ids = new ArrayList<>();
                    void run(int id, Integer boxed, List<String> names, Set<Integer> unique) {
                        ids.remove(id);
                        ids.remove(5);
                        ids.remove(boxed);
                        ids.remove(Integer.valueOf(id));
                        names.remove(id);
                        unique.remove(id);
                        for (int i = 0; i < ids.size(); i++) {
                            ids.remove(i);
                        }
                    }
                }
                """)).containsExactly(4, 5);
    }

    @Test
    void charInStringBuilderConstructorIsCapacity() {
        List<Violation> violations = RuleTests.check(new StringBuilderCharConstructorRule(), """
                class Sample {
                    String run(char first, String text, int size) {
                        StringBuilder one = new StringBuilder('a');
                        StringBuffer two = new StringBuffer(first);
                        StringBuilder three = new StringBuilder("a");
                        StringBuilder four = new StringBuilder(size);
                        StringBuilder five = new StringBuilder(text);
                        return one.toString();
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(3, 4);
        assertThat(new StringBuilderCharConstructorRule().confidence()).isEqualTo(Confidence.CONFIRMED);
    }

    @Test
    void constructorMustNotCallOverriddenMethod() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Base.java", """
                class Base {
                    Base() {
                        init();
                        this.prepare(1);
                        helper();
                        fixed();
                        Runnable later = () -> init();
                    }
                    void init() {}
                    void prepare(int size) {}
                    private void helper() {}
                    final void fixed() {}
                    void unused() {}
                }
                """);
        files.put("Child.java", """
                class Child extends Base {
                    private final List<String> items = new ArrayList<>();
                    @Override
                    void init() { items.add("x"); }
                    void fixed(int other) {}
                }
                """);
        files.put("Grandchild.java", "class Grandchild extends Child { @Override void prepare(int size) {} }");
        files.put("Alone.java", "class Alone { Alone() { init(); } void init() {} }");

        List<Violation> violations = RuleTests.checkProject(new OverridableCallInConstructorRule(), files);

        // prepare переопределен через поколение; у Alone наследников нет
        assertThat(violations).extracting(Violation::line).containsExactly(3, 4);
        assertThat(violations.get(0).message()).contains("'Child'");
        assertThat(violations.get(1).message()).contains("'Grandchild'");
    }

    @Test
    void keyMustNotChangeAfterInsert() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Point.java", "@Data class Point { private int x; }");
        files.put("Session.java", "class Session { private String name; void setName(String name) {} }");
        files.put("Sample.java", """
                class Sample {
                    void run(Map<Point, String> names, Set<Point> points, Map<Session, String> sessions, List<Point> list) {
                        Point key = new Point();
                        key.setX(1);
                        names.put(key, "a");
                        key.setX(2);
                        Point member = new Point();
                        points.add(member);
                        member.setX(3);
                        Session session = new Session();
                        sessions.put(session, "b");
                        session.setName("c");
                        Point listed = new Point();
                        list.add(listed);
                        listed.setX(4);
                    }
                }
                """);

        // Session ищется по ссылке: своего hashCode у него нет; список от hashCode не зависит
        assertThat(RuleTests.checkProject(new MutableMapKeyRule(), files)).extracting(Violation::line).containsExactly(6, 9);
    }

    @Test
    void serializableClassNeedsVersionAndSerializableFields() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Address.java", "class Address { private String city; }");
        files.put("Money.java", "class Money implements Serializable { private static final long serialVersionUID = 1L; }");
        files.put("Tag.java", "class Tag extends Money { }");
        files.put("External.java", "class External extends LibraryBase { }");
        files.put("Order.java", """
                class Order implements Serializable {
                    private String title;
                    private Address address;
                    private Money total;
                    private List<Address> history;
                    private Tag tag;
                    private External external;
                    private transient Address cached;
                    private static Address shared;
                    private Thread worker;
                    private Remote remote;
                }
                """);
        files.put("Plain.java", "class Plain { private Address address; }");

        assertThat(RuleTests.checkProject(new NonSerializableFieldRule(), files))
                .extracting(Violation::line).containsExactly(3, 5, 10);
        assertThat(RuleTests.lines(new SerializableWithoutSerialVersionUidRule(), files.get("Order.java"))).containsExactly(1);
        assertThat(RuleTests.lines(new SerializableWithoutSerialVersionUidRule(), files.get("Money.java"))).isEmpty();
        assertThat(RuleTests.lines(new SerializableWithoutSerialVersionUidRule(), files.get("Plain.java"))).isEmpty();
    }

    @Test
    void equalsWithInstanceofBreaksSymmetryInHierarchy() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Point.java", """
                class Point {
                    private int x;
                    @Override
                    public boolean equals(Object other) {
                        return other instanceof Point point && point.x == x;
                    }
                }
                """);
        files.put("ColorPoint.java", """
                class ColorPoint extends Point {
                    private String color;
                    @Override
                    public boolean equals(Object other) {
                        return other instanceof ColorPoint point && super.equals(other) && point.color.equals(color);
                    }
                }
                """);
        files.put("Strict.java", """
                class Strict {
                    private int x;
                    @Override
                    public boolean equals(Object other) {
                        return other != null && getClass() == other.getClass() && ((Strict) other).x == x;
                    }
                }
                """);
        files.put("StrictChild.java", """
                class StrictChild extends Strict {
                    private int y;
                    @Override
                    public boolean equals(Object other) {
                        return super.equals(other) && other instanceof StrictChild child && child.y == y;
                    }
                }
                """);

        List<Violation> violations = RuleTests.checkProject(new EqualsInstanceofInHierarchyRule(), files);

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.file()).hasFileName("ColorPoint.java");
            assertThat(violation.line()).isEqualTo(3);
        });
    }

    @Test
    void mapperShouldFillEveryAvailableField() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("User.java", "class User { private Long id; private String name; private String email; private String phone; private String password; }");
        files.put("UserDto.java", "@Data class UserDto { private Long id; private String name; private String email; private String phone; private String link; }");
        files.put("UserView.java", "@Builder class UserView { private Long id; private String name; private String email; private boolean active = true; }");
        files.put("Mapper.java", """
                class Mapper {
                    UserDto toDto(User user) {
                        UserDto dto = new UserDto();
                        dto.setId(user.getId());
                        dto.setName(user.getName());
                        dto.setEmail(user.getEmail());
                        return dto;
                    }
                    UserDto full(User user) {
                        UserDto dto = new UserDto();
                        dto.setId(user.getId());
                        dto.setName(user.getName());
                        dto.setEmail(user.getEmail());
                        dto.setPhone(user.getPhone());
                        return dto;
                    }
                    UserDto partial(User user) {
                        UserDto dto = new UserDto();
                        dto.setId(user.getId());
                        return dto;
                    }
                    UserDto delegated(User user) {
                        UserDto dto = new UserDto();
                        dto.setId(user.getId());
                        dto.setName(user.getName());
                        fillContacts(dto, user);
                        return dto;
                    }
                    UserView toView(User user) {
                        return UserView.builder()
                                .id(user.getId())
                                .name(user.getName())
                                .build();
                    }
                    UserDto create(String name) {
                        UserDto dto = new UserDto();
                        dto.setName(name);
                        dto.setLink("x");
                        return dto;
                    }
                }
                """);

        List<Violation> violations = RuleTests.checkProject(new MapperMissesFieldRule(), files);

        // link в исходном объекте нет - заполнять его нечем; active у построителя имеет значение по умолчанию
        assertThat(violations).extracting(Violation::line).containsExactly(3, 30);
        assertThat(violations.get(0).message()).contains("поле 'phone'").doesNotContain("link");
        assertThat(violations.get(1).message()).contains("поле 'email'").doesNotContain("active");
    }

    @Test
    void externalServicesShouldUseHttps() {
        ConfigFile config = new ConfigFile(Path.of("application.properties"), List.of(
                new ConfigProperty("payments.url", "http://api.payments.com/v1", 1),
                new ConfigProperty("auth.url", "https://auth.example.org", 2),
                new ConfigProperty("local.url", "http://localhost:8080", 3),
                new ConfigProperty("orders.url", "http://orders:8080/api", 4),
                new ConfigProperty("cluster.url", "http://orders.default.svc.cluster.local", 5),
                new ConfigProperty("private.url", "http://10.0.0.5:9000", 6),
                new ConfigProperty("fallback.url", "${MAIL_URL:http://mail.partner.ru}", 7),
                new ConfigProperty("schema", "http://www.w3.org/2001/XMLSchema", 8),
                new ConfigProperty("public.address", "http://8.8.8.8/dns", 9)));
        ConfigFile development = new ConfigFile(Path.of("application-dev.properties"), List.of(
                new ConfigProperty("payments.url", "http://api.payments.com/v1", 1)));

        assertThat(new InsecureHttpUrlRule().checkConfig(config)).extracting(Violation::line).containsExactly(1, 7, 9);
        assertThat(new InsecureHttpUrlRule().checkConfig(development)).isEmpty();
    }

    @Test
    void webApplicationShouldStopGracefully() throws IOException {
        write("pom.xml", """
                <project>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.2.5</version>
                  </parent>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);
        write("src/main/resources/application.properties", "server.port=8080\n");

        List<Violation> missing = new GracefulShutdownMissingRule().checkContext(context());
        assertThat(missing).singleElement().satisfies(violation -> {
            assertThat(violation.file()).hasFileName("application.properties");
            assertThat(violation.message()).contains("Spring Boot 3.2");
        });

        write("src/main/resources/application.properties", "server.port=8080\nserver.shutdown=graceful\n");
        assertThat(new GracefulShutdownMissingRule().checkContext(context())).isEmpty();

        write("src/main/resources/application.properties", "server.port=8080\nserver.shutdown=immediate\n");
        List<Violation> disabled = new GracefulShutdownMissingRule().checkContext(context());
        assertThat(disabled).extracting(Violation::line).containsExactly(2);
        assertThat(disabled).extracting(Violation::confidence).containsExactly(Confidence.CONFIRMED);
    }

    @Test
    void newSpringBootStopsGracefullyByDefault() throws IOException {
        write("build.gradle", """
                plugins {
                    id 'org.springframework.boot' version '3.4.1'
                }
                dependencies {
                    implementation 'org.springframework.boot:spring-boot-starter-web'
                }
                """);
        write("src/main/resources/application.properties", "server.port=8080\n");

        assertThat(new GracefulShutdownMissingRule().checkContext(context())).isEmpty();

        // Приложение без веб-сервера останавливать плавно незачем
        write("build.gradle", """
                plugins {
                    id 'org.springframework.boot' version '3.1.0'
                }
                dependencies {
                    implementation 'org.springframework.boot:spring-boot-starter'
                }
                """);
        assertThat(new GracefulShutdownMissingRule().checkContext(context())).isEmpty();
    }

    private ScanContext context() {
        return new ScanContext(
                dir,
                List.of(),
                new FileSystemConfigLoader().load(dir, file -> true),
                new FileSystemTextFileLoader().load(dir, file -> true));
    }

    private void write(String path, String content) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
