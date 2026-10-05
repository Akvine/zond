package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.loaders.FileSystemSourceLoader;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.concurrency.MutableStateInSingletonBeanRule;
import ru.akvine.zond.rules.logical.IgnoredResultRule;
import ru.akvine.zond.rules.resources.UnclosedResourceRule;
import ru.akvine.zond.rules.support.LocalTypes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Код, который Lombok создает при компиляции: в исходниках его нет, но типы и поведение должны быть известны
 */
class LombokTest {
    private static final String USER = """
            package demo;

            import java.math.BigDecimal;

            @Data
            @Builder(toBuilder = true)
            @Accessors(chain = true)
            public class User {
                private String name;
                private boolean active;
                private BigDecimal balance;
                @With
                private int age;
            }
            """;

    private static final String POINT = """
            package demo;

            @Getter
            @Setter
            @Accessors(fluent = true)
            public class Point {
                private double x;
            }
            """;

    private static final String PLAIN = """
            package demo;

            public class Plain {
                private String name;
            }
            """;

    @TempDir
    Path dir;

    @Test
    void knowsTypesOfGeneratedMethods() throws IOException {
        List<SourceFile> sources = load(Map.of("User", USER, "Point", POINT, "Plain", PLAIN, "Sample", """
                package demo;

                class Sample {
                    void run(User user, Point point, Plain plain) {
                        use(user.getName());
                        use(user.isActive());
                        use(user.setName("x"));
                        use(user.setName("x").getBalance());
                        use(user.withAge(3));
                        use(User.builder().name("x").active(true).build());
                        use(User.builder().build().getBalance());
                        use(user.toBuilder().name("y").build());
                        use(point.x());
                        use(point.x(1.0));
                        use(plain.getName());
                        use(plain.setName("x"));
                        use(Plain.builder().build());
                    }
                }
                """));

        assertThat(typeOf(sources, "user.getName()")).contains("String");
        assertThat(typeOf(sources, "user.isActive()")).contains("boolean");
        // @Accessors(chain = true): сеттер возвращает сам объект
        assertThat(typeOf(sources, "user.setName(\"x\")")).contains("User");
        assertThat(typeOf(sources, "user.setName(\"x\").getBalance()")).contains("BigDecimal");
        assertThat(typeOf(sources, "user.withAge(3)")).contains("User");
        assertThat(typeOf(sources, "User.builder().name(\"x\").active(true).build()")).contains("User");
        assertThat(typeOf(sources, "User.builder().build().getBalance()")).contains("BigDecimal");
        assertThat(typeOf(sources, "user.toBuilder().name(\"y\").build()")).contains("User");
        // @Accessors(fluent = true): геттер и сеттер названы как поле
        assertThat(typeOf(sources, "point.x()")).contains("double");
        assertThat(typeOf(sources, "point.x(1.0)")).contains("Point");
        // Без аннотаций Lombok ничего не создаст
        assertThat(typeOf(sources, "plain.getName()")).isEmpty();
        assertThat(typeOf(sources, "plain.setName(\"x\")")).isEmpty();
        assertThat(typeOf(sources, "Plain.builder().build()")).isEmpty();
    }

    @Test
    void knowsGetterCalledInsideOwnClass() throws IOException {
        List<SourceFile> sources = load(Map.of("Box", """
                package demo;

                import java.util.List;

                @Getter
                public class Box {
                    private List<String> items;

                    void print() {
                        use(getItems());
                        use(this.getItems());
                    }
                }
                """));

        assertThat(typeOf(sources, "getItems()")).contains("List");
        assertThat(typeOf(sources, "this.getItems()")).contains("List");
    }

    @Test
    void knowsLoggerFieldAndVal() throws IOException {
        List<SourceFile> sources = load(Map.of(
                "User", USER,
                "Logged", """
                        package demo;

                        @Slf4j
                        class Logged {
                            void run(User user) {
                                val balance = user.getBalance();
                                use(balance);
                                use(log);
                            }
                        }
                        """,
                "Silent", """
                        package demo;

                        class Silent {
                            void run() {
                                use(log);
                            }
                        }
                        """));

        assertThat(typeOf(sources.stream().filter(source -> isFile(source, "Logged")).toList(), "balance"))
                .contains("BigDecimal");
        assertThat(typeOf(sources.stream().filter(source -> isFile(source, "Logged")).toList(), "log"))
                .contains("Logger");
        assertThat(typeOf(sources.stream().filter(source -> isFile(source, "Silent")).toList(), "log")).isEmpty();
    }

    @Test
    void rulesSeeThroughBuilderChain() throws IOException {
        List<SourceFile> sources = load(Map.of("User", USER, "Sample", """
                package demo;

                class Sample {
                    void run() {
                        User.builder().name("x").build().getName().trim();
                    }
                }
                """));
        IgnoredResultRule rule = new IgnoredResultRule();

        List<Integer> lines = sources.stream()
                .flatMap(source -> rule.check(source).stream())
                .map(Violation::line)
                .toList();

        assertThat(lines).containsExactly(5);
    }

    @Test
    void generatedSetterMakesSingletonStateMutable() {
        assertThat(RuleTests.check(new MutableStateInSingletonBeanRule(), """
                @Service
                @Setter
                class Counter {
                    private int count;
                    private final String name = "counter";
                    private static int total;
                }
                """)).singleElement().satisfies(violation -> {
            assertThat(violation.line()).isEqualTo(4);
            assertThat(violation.message()).contains("'setCount' (сеттер от Lombok)");
        });
    }

    @Test
    void cleanupClosesResource() {
        assertThat(RuleTests.lines(new UnclosedResourceRule(), """
                class Sample {
                    void run() throws Exception {
                        @Cleanup FileReader managed = new FileReader("a.txt");
                        FileReader forgotten = new FileReader("b.txt");
                    }
                }
                """)).containsExactly(4);
    }

    /**
     * @param files имя класса -> исходный код; файлы кладутся в каталог пакета demo и загружаются с решателем типов
     */
    private List<SourceFile> load(Map<String, String> files) throws IOException {
        Path packageDirectory = Files.createDirectories(dir.resolve("demo"));
        for (Map.Entry<String, String> file : files.entrySet()) {
            Files.writeString(packageDirectory.resolve(file.getKey() + ".java"), file.getValue());
        }
        return new FileSystemSourceLoader().load(dir).sources();
    }

    private boolean isFile(SourceFile source, String className) {
        return source.path().getFileName().toString().equals(className + ".java");
    }

    // Тип выражения, переданного в use(...)
    private Optional<String> typeOf(List<SourceFile> sources, String text) {
        Expression argument = sources.stream()
                .flatMap(source -> source.unit().findAll(MethodCallExpr.class).stream())
                .filter(call -> call.getNameAsString().equals("use") && call.getArgument(0).toString().equals(text))
                .map(call -> call.getArgument(0))
                .findFirst()
                .orElseThrow(() -> new AssertionError("В коде нет use(" + text + ")"));
        return LocalTypes.typeOf(argument);
    }
}
