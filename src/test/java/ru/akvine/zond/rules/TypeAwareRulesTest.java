package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.loaders.FileSystemSourceLoader;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.rules.codesmell.CheckManualServiceCreationRule;
import ru.akvine.zond.rules.codesmell.CheckRepositoryInControllerRule;
import ru.akvine.zond.rules.concurrency.CheckLockWithoutFinallyRule;
import ru.akvine.zond.rules.logical.CheckLogPlaceholderMismatchRule;
import ru.akvine.zond.rules.logical.CheckTransactionalRollbackForCheckedExceptionRule;
import ru.akvine.zond.rules.logical.CheckTransactionalSelfInvocationRule;
import ru.akvine.zond.rules.performance.CheckMissingBatchProcessingRule;
import ru.akvine.zond.rules.resources.CheckTransactionalFileIoRule;
import ru.akvine.zond.rules.resources.CheckTransactionalHttpCallRule;
import ru.akvine.zond.rules.resources.CheckUnclosedResourceRule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила, которые судят по настоящему типу, а не по имени: проверяются на проекте из нескольких файлов,
 * загруженном вместе с решателем типов
 */
class TypeAwareRulesTest {
    private static final String PACKAGE = "demo";
    private static final String JAVA_EXTENSION = ".java";

    @TempDir
    Path dir;

    @Test
    void selfInvocationTellsOverloadsApart() throws IOException {
        List<String> found = check(new CheckTransactionalSelfInvocationRule(), Map.of("Orders", """
                package demo;

                public class Orders {
                    public void run(Long id, String name) {
                        save(id);
                        save(name);
                    }

                    @Transactional
                    public void save(String name) {}

                    public void save(Long id) {}
                }
                """));

        // save(id) приходится на перегрузку без @Transactional
        assertThat(found).containsExactly("Orders:6");
    }

    @Test
    void uncheckedExceptionIsKnownByItsAncestors() throws IOException {
        List<String> found = check(new CheckTransactionalRollbackForCheckedExceptionRule(), Map.of(
                "AppException", """
                        package demo;

                        public class AppException extends RuntimeException {
                        }
                        """,
                "AppFailure", """
                        package demo;

                        public class AppFailure extends Exception {
                        }
                        """,
                "Jobs", """
                        package demo;

                        public class Jobs {
                            @Transactional
                            public void unchecked() throws AppException {}

                            @Transactional
                            public void checked() throws AppFailure {}
                        }
                        """));

        assertThat(found).containsExactly("Jobs:7");
    }

    @Test
    void lockIsKnownByItsAncestors() throws IOException {
        List<String> found = check(new CheckLockWithoutFinallyRule(), Map.of(
                "OrderLock", """
                        package demo;

                        public class OrderLock extends java.util.concurrent.locks.ReentrantLock {
                        }
                        """,
                "Guarded", """
                        package demo;

                        public class Guarded {
                            private final OrderLock guard = new OrderLock();

                            void run() {
                                guard.lock();
                                work();
                                guard.unlock();
                            }
                        }
                        """));

        assertThat(found).containsExactly("Guarded:7");
    }

    @Test
    void ownClassNamedLikeResourceIsNotResource() throws IOException {
        List<String> found = check(new CheckUnclosedResourceRule(), Map.of(
                "Socket", """
                        package demo;

                        public class Socket {
                        }
                        """,
                "Info", """
                        package demo;

                        public class Info {
                        }
                        """,
                "Registry", """
                        package demo;

                        public class Registry {
                            public Info getConnection() { return new Info(); }
                        }
                        """,
                "Sample", """
                        package demo;

                        class Sample {
                            void run(Registry registry) throws Exception {
                                Socket socket = new Socket();
                                Info info = registry.getConnection();
                                java.io.FileReader reader = new java.io.FileReader("a.txt");
                            }
                        }
                        """));

        assertThat(found).containsExactly("Sample:7");
    }

    @Test
    void httpClientIsKnownByType() throws IOException {
        List<String> found = check(new CheckTransactionalHttpCallRule(), Map.of(
                "Billing", """
                        package demo;

                        @FeignClient(name = "billing")
                        public interface Billing {
                            void charge();
                        }
                        """,
                "Payments", """
                        package demo;

                        public class Payments {
                            private java.util.concurrent.Exchanger<String> exchanger;
                            private java.util.Map<String, String> client;
                            private java.net.URL url;
                            private Billing billing;

                            @Transactional
                            public void pay() throws Exception {
                                exchanger.exchange("x");
                                client.get("x");
                                url.getHost();
                                url.openStream();
                                billing.charge();
                            }
                        }
                        """));

        assertThat(found).containsExactly("Payments:14", "Payments:15");
    }

    @Test
    void streamCopyIsNotFileOperation() throws IOException {
        List<String> found = check(new CheckTransactionalFileIoRule(), Map.of("Uploads", """
                package demo;

                public class Uploads {
                    @Transactional
                    public void store(java.io.InputStream input, java.io.OutputStream output, MultipartFile file)
                            throws Exception {
                        input.transferTo(output);
                        file.transferTo(output);
                    }
                }
                """));

        assertThat(found).containsExactly("Uploads:8");
    }

    @Test
    void manuallyCreatedBeanIsKnownByStereotype() throws IOException {
        List<String> found = check(new CheckManualServiceCreationRule(), Map.of(
                "ReportService", """
                        package demo;

                        public class ReportService {
                        }
                        """,
                "MailService", """
                        package demo;

                        @Service
                        public class MailService {
                        }
                        """,
                "Sample", """
                        package demo;

                        class Sample {
                            void run() {
                                use(new ReportService());
                                use(new MailService());
                            }
                        }
                        """));

        assertThat(found).containsExactly("Sample:6");
    }

    @Test
    void repositoryDependencyIsKnownByAncestors() throws IOException {
        List<String> found = check(new CheckRepositoryInControllerRule(), Map.of(
                "Users", """
                        package demo;

                        public interface Users extends JpaRepository<User, Long> {
                        }
                        """,
                "UsersController", """
                        package demo;

                        @RestController
                        public class UsersController {
                            private final Users users;

                            UsersController(Users users) {
                                this.users = users;
                            }
                        }
                        """));

        assertThat(found).containsExactly("UsersController:3");
    }

    @Test
    void exceptionArgumentOfLogIsKnownByType() throws IOException {
        List<String> found = check(new CheckLogPlaceholderMismatchRule(), Map.of("Sample", """
                package demo;

                class Sample {
                    void run(IllegalStateException problem, String id) {
                        log.error("Failed {}", id, problem);
                        log.error("Failed {}", id, id);
                    }
                }
                """));

        assertThat(found).containsExactly("Sample:6");
    }

    @Test
    void jdbcWriteInLoopIsKnownByType() throws IOException {
        List<String> found = check(new CheckMissingBatchProcessingRule(), Map.of(
                "Counter", """
                        package demo;

                        public class Counter {
                            public int executeUpdate() { return 1; }
                        }
                        """,
                "Sample", """
                        package demo;

                        import java.sql.PreparedStatement;
                        import java.util.List;

                        class Sample {
                            void run(List<String> names, PreparedStatement insert, Counter counter) throws Exception {
                                for (String name : names) {
                                    insert.executeUpdate();
                                    counter.executeUpdate();
                                }
                            }
                        }
                        """));

        assertThat(found).containsExactly("Sample:9");
    }

    /**
     * @param files имя класса -> исходный код; файлы кладутся в каталог пакета demo
     * @return найденные нарушения в виде "Класс:строка"
     */
    private List<String> check(Rule rule, Map<String, String> files) throws IOException {
        Path packageDirectory = Files.createDirectories(dir.resolve(PACKAGE));
        for (Map.Entry<String, String> file : files.entrySet()) {
            Files.writeString(packageDirectory.resolve(file.getKey() + JAVA_EXTENSION), file.getValue());
        }

        List<SourceFile> sources = new FileSystemSourceLoader().load(dir).sources();
        return sources.stream()
                .flatMap(source -> rule.check(source).stream())
                .map(violation -> violation.file().getFileName().toString().replace(JAVA_EXTENSION, "")
                        + ":" + violation.line())
                .sorted()
                .toList();
    }
}
