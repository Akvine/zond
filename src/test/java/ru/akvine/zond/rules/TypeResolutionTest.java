package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.loaders.FileSystemSourceLoader;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.logical.IgnoredResultRule;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loggers;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Repositories;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Разрешение типов на файлах, загруженных так же, как при настоящем сканировании: с решателем типов
 */
class TypeResolutionTest {
    private static final String PACKAGE = "demo";

    @TempDir
    Path dir;

    @Test
    void resolvesTypesDeclaredInOtherFiles() throws IOException {
        List<SourceFile> sources = load(Map.of(
                "Account", """
                        package demo;

                        import java.math.BigDecimal;
                        import java.util.List;

                        public class Account {
                            public double rate;

                            public BigDecimal balance() { return BigDecimal.ZERO; }
                            public List<String> owners() { return List.of(); }
                            public byte[] raw() { return new byte[0]; }
                        }
                        """,
                "Sample", """
                        package demo;

                        class Sample {
                            void run(Account account) {
                                use(account.balance());
                                use(account.owners().get(0));
                                use(account.raw());
                                use(account.rate);
                                var copy = account;
                                use(copy.balance().negate());
                                use(account.missing());
                            }
                        }
                        """));

        assertThat(typeOfArgument(sources, "account.balance()")).contains("BigDecimal");
        assertThat(typeOfArgument(sources, "account.owners().get(0)")).contains("String");
        assertThat(typeOfArgument(sources, "account.raw()")).contains("byte[]");
        assertThat(typeOfArgument(sources, "account.rate")).contains("double");
        assertThat(typeOfArgument(sources, "copy.balance().negate()")).contains("BigDecimal");
        // Метода нет - тип неизвестен, но сканирование не падает
        assertThat(typeOfArgument(sources, "account.missing()")).isEmpty();
    }

    @Test
    void classNameIsNotValue() throws IOException {
        List<SourceFile> sources = load(Map.of("Sample", """
                package demo;

                class Sample {
                    String run(int number) {
                        return String.valueOf(number);
                    }
                }
                """));

        MethodCallExpr call = call(sources, "String.valueOf(number)");

        assertThat(LocalTypes.typeOf(call.getScope().orElseThrow())).isEmpty();
        assertThat(LocalTypes.typeOf(call)).contains("String");
    }

    @Test
    void takesTypeOfLombokGetterFromField() throws IOException {
        List<SourceFile> sources = load(Map.of(
                "User", """
                        package demo;

                        @Data
                        public class User {
                            private String name;
                            private boolean active;
                        }
                        """,
                "Plain", """
                        package demo;

                        public class Plain {
                            private String name;
                        }
                        """,
                "Sample", """
                        package demo;

                        class Sample {
                            void run(User user, Plain plain) {
                                use(user.getName());
                                use(user.isActive());
                                use(plain.getName());
                            }
                        }
                        """));

        assertThat(typeOfArgument(sources, "user.getName()")).contains("String");
        assertThat(typeOfArgument(sources, "user.isActive()")).contains("boolean");
        // Геттера нет ни в коде, ни от Lombok
        assertThat(typeOfArgument(sources, "plain.getName()")).isEmpty();
    }

    @Test
    void resolvesLibraryTypesOnlyWithClasspath() throws IOException, URISyntaxException {
        Map<String, String> files = Map.of("Sample", """
                package demo;

                import org.assertj.core.api.SoftAssertions;

                class Sample {
                    void run(SoftAssertions softly) {
                        use(softly.errorsCollected());
                    }
                }
                """);
        Path library = Path.of(SoftAssertions.class.getProtectionDomain().getCodeSource().getLocation().toURI());

        assertThat(typeOfArgument(load(files), "softly.errorsCollected()")).isEmpty();
        assertThat(typeOfArgument(load(files, List.of(library)), "softly.errorsCollected()")).contains("List");
    }

    @Test
    void knowsRepositoryByTypeNotByName() throws IOException {
        List<SourceFile> sources = load(Map.of(
                "Users", """
                        package demo;

                        public interface Users extends JpaRepository<User, Long> {
                        }
                        """,
                "Storage", """
                        package demo;

                        @Repository
                        public class Storage {
                            public void store(String value) {}
                        }
                        """,
                "Sample", """
                        package demo;

                        class Sample {
                            private Users users;
                            private Storage storage;
                            private String userDao;
                            private Unknown orderRepository;

                            void run() {
                                users.findAll();
                                storage.store("x");
                                userDao.length();
                                orderRepository.findAll();
                            }
                        }
                        """));

        // Предок JpaRepository в библиотеке, которой нет, - он известен только по имени из extends
        assertThat(Repositories.isRepositoryCall(call(sources, "users.findAll()"))).isTrue();
        assertThat(Repositories.isRepositoryCall(call(sources, "storage.store(\"x\")"))).isTrue();
        // Имя похоже на репозиторий, но это строка
        assertThat(Repositories.isRepositoryCall(call(sources, "userDao.length()"))).isFalse();
        // Тип неизвестен - остается проверка по имени
        assertThat(Repositories.isRepositoryCall(call(sources, "orderRepository.findAll()"))).isTrue();
    }

    @Test
    void knowsLoggerByType() throws IOException {
        List<SourceFile> sources = load(Map.of("Sample", """
                package demo;

                import java.util.logging.Logger;

                class Sample {
                    private final Logger audit = Logger.getLogger("audit");
                    private final StringBuilder log = new StringBuilder();

                    void run() {
                        audit.info("done");
                        log.append("done");
                        log.info("done");
                        logger.info("done");
                    }
                }
                """));

        assertThat(Loggers.isLogCall(call(sources, "audit.info(\"done\")"))).isTrue();
        assertThat(Loggers.isLogCall(call(sources, "log.info(\"done\")"))).isFalse();
        assertThat(Loggers.isLogCall(call(sources, "logger.info(\"done\")"))).isTrue();
    }

    @Test
    void ownClassIsNotLibraryClassWithSameName() throws IOException {
        List<SourceFile> sources = load(Map.of(
                "Files", """
                        package demo;

                        public class Files {
                            public static String readString(String name) { return name; }
                        }
                        """,
                "Sample", """
                        package demo;

                        class Sample {
                            void run() throws Exception {
                                Files.readString("a.txt");
                                java.nio.file.Files.readString(java.nio.file.Path.of("a.txt"));
                            }
                        }
                        """));

        assertThat(MethodCalls.isCallOn(call(sources, "Files.readString(\"a.txt\")"), "Files", "readString"))
                .isFalse();
        assertThat(MethodCalls.isCallOn(
                call(sources, "java.nio.file.Files.readString(java.nio.file.Path.of(\"a.txt\"))"),
                "Files", "readString")).isTrue();
    }

    @Test
    void rulesSeeTypesFromOtherFiles() throws IOException {
        List<SourceFile> sources = load(Map.of(
                "Holder", """
                        package demo;

                        public class Holder {
                            public String name() { return "x"; }
                            public StringBuilder builder() { return new StringBuilder(); }
                        }
                        """,
                "Sample", """
                        package demo;

                        class Sample {
                            void run(Holder holder) {
                                holder.name().trim();
                                holder.builder().reverse();
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

    private List<SourceFile> load(Map<String, String> files) throws IOException {
        return load(files, List.of());
    }

    /**
     * @param files имя класса -> исходный код; файлы кладутся в каталог пакета demo
     */
    private List<SourceFile> load(Map<String, String> files, List<Path> classpath) throws IOException {
        Path packageDirectory = Files.createDirectories(dir.resolve(PACKAGE));
        for (Map.Entry<String, String> file : files.entrySet()) {
            Files.writeString(packageDirectory.resolve(file.getKey() + ".java"), file.getValue());
        }
        return new FileSystemSourceLoader().load(dir, path -> true, classpath).sources();
    }

    private MethodCallExpr call(List<SourceFile> sources, String text) {
        return sources.stream()
                .flatMap(source -> source.unit().findAll(MethodCallExpr.class).stream())
                .filter(call -> call.toString().equals(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("В коде нет вызова " + text));
    }

    // Тип выражения, переданного в use(...)
    private Optional<String> typeOfArgument(List<SourceFile> sources, String text) {
        Expression argument = sources.stream()
                .flatMap(source -> source.unit().findAll(MethodCallExpr.class).stream())
                .filter(call -> call.getNameAsString().equals("use") && call.getArgument(0).toString().equals(text))
                .map(call -> call.getArgument(0))
                .findFirst()
                .orElseThrow(() -> new AssertionError("В коде нет use(" + text + ")"));
        return LocalTypes.typeOf(argument);
    }
}
