package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.loaders.FileSystemSourceLoader;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class UnusedDeclarationRulesTest {
    private static final Pattern QUOTED_NAME = Pattern.compile("'([^']+)'");

    private static final String MAIN = """
            class Main {
                public static void main(String[] args) {
                    Report report = new Report();
                    report.print();
                    Shape shape = new Circle();
                    shape.area();
                    List.of(report).forEach(Report::archive);
                    new Jobs();
                    new Task().run();
                    String reflective = "calledByName";
                }
            }
            """;

    private static final Map<String, String> PROJECT = Map.of(
            "Main.java", MAIN,
            "Report.java", """
                    class Report {
                        void print() {}
                        void archive() {}
                        void unusedHelper() {}
                        void calledByName() {}
                        void recursive() { recursive(); }
                        String getTitle() { return ""; }
                        public String toString() { return ""; }
                        private void hidden() {}
                    }
                    """,
            "Orphan.java", """
                    class Orphan {
                        void run() {}

                        static class Inner {
                        }
                    }
                    """,
            "Jobs.java", """
                    @Component
                    class Jobs {
                        @Scheduled(fixedDelay = 1000)
                        void tick() {}

                        @Deprecated
                        void neverCalled() {}
                    }
                    """,
            "Controller.java", """
                    @RestController
                    class Controller {
                        @GetMapping("/")
                        String index() { return ""; }
                    }
                    """,
            "Shape.java", """
                    interface Shape {
                        double area();
                        double perimeter();
                    }
                    """,
            "Circle.java", """
                    class Circle implements Shape {
                        public double area() { return 0; }
                        public double perimeter() { return 0; }
                    }
                    """,
            "Task.java", """
                    class Task implements Runnable {
                        public void run() {}
                        public void cancel() {}
                        void extra() {}
                    }
                    """,
            "Marker.java", """
                    @interface Marker {
                        String value();
                    }
                    """);

    @TempDir
    Path dir;

    @Test
    void findsMethodsNobodyCalls() {
        assertThat(names(RuleTests.checkProject(new CheckUnusedMethodRule(), PROJECT))).containsExactlyInAnyOrder(
                // Вызов из самого себя использованием не считается
                "Report.unusedHelper", "Report.recursive",
                // @Deprecated не делает метод точкой входа, в отличие от @Scheduled
                "Jobs.neverCalled",
                // Через интерфейс вызывают только area(); реализация perimeter() нужна, пока метод есть в интерфейсе
                "Shape.perimeter",
                // cancel() может переопределять метод предка не из проекта, а непубличный extra() - не может
                "Task.extra");
    }

    @Test
    void findsClassesNobodyRefersTo() {
        // Controller создает Spring, Marker - аннотация, вложенный Inner отдельно не перечисляется
        assertThat(names(RuleTests.checkProject(new CheckUnusedClassRule(), PROJECT))).containsExactly("Orphan");
    }

    @Test
    void codeReachedOnlyFromDeadCodeIsDeadToo() {
        Map<String, String> files = Map.of(
                "Main.java", "class Main { public static void main(String[] args) { new Service().run(); } }",
                "Service.java", """
                        class Service {
                            void run() { step(); }
                            void step() {}
                            void legacy() { helper(); new Formatter().format(); }
                            void helper() { deep(); }
                            private void deep() {}
                            private void lonely() {}
                        }
                        """,
                "Formatter.java", "class Formatter { void format() {} }",
                "Old.java", "class Old { void run() { new OldHelper().help(); } }",
                "OldHelper.java", "class OldHelper { void help() {} }",
                "Ping.java", "class Ping { Pong pong; }",
                "Pong.java", "class Pong { Ping ping; }");

        List<Violation> methods = RuleTests.checkProject(new CheckUnusedMethodRule(), files);
        // lonely() без единого вызова находит правило о приватных методах, а deep() мертв из-за цепочки
        assertThat(names(methods)).containsExactlyInAnyOrder(
                "Service.legacy", "Service.helper", "Service.deep", "Formatter.format");
        assertThat(messageAbout(methods, "Service.legacy")).contains("нигде в проекте не вызывается");
        assertThat(messageAbout(methods, "Service.helper"))
                .contains("вызывается только из неиспользуемого кода (Service.legacy)");
        assertThat(messageAbout(methods, "Formatter.format")).contains("(Service.legacy)");

        List<Violation> classes = RuleTests.checkProject(new CheckUnusedClassRule(), files);
        // Ping и Pong ссылаются только друг на друга
        assertThat(names(classes)).containsExactlyInAnyOrder("Old", "OldHelper", "Ping", "Pong");
        assertThat(messageAbout(classes, "Old")).contains("нигде в проекте не используется");
        assertThat(messageAbout(classes, "OldHelper"))
                .contains("используется только в неиспользуемых классах (Old)");
    }

    private String messageAbout(List<Violation> violations, String name) {
        return violations.stream()
                .map(Violation::message)
                .filter(message -> message.contains("'" + name + "'"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Нет находки о " + name));
    }

    @Test
    void singleFileIsNotEnoughToJudge() {
        assertThat(RuleTests.check(new CheckUnusedMethodRule(), "class Report { void print() {} }")).isEmpty();
        assertThat(RuleTests.check(new CheckUnusedClassRule(), "class Report { void print() {} }")).isEmpty();
    }

    @Test
    void classUsedOnlyByItselfIsUnused() {
        Map<String, String> files = Map.of(
                "Node.java", "class Node { Node next; static Node empty() { return new Node(); } }",
                "Main.java", "class Main { public static void main(String[] args) {} }");

        assertThat(names(RuleTests.checkProject(new CheckUnusedClassRule(), files))).containsExactly("Node");
    }

    @Test
    void libraryCallDoesNotKeepProjectMethodAlive() throws IOException {
        Map<String, String> files = Map.of(
                "Basket", """
                        package demo;

                        public class Basket {
                            void add(String item) {}
                            void clear() {}
                            void pay() {}
                        }
                        """,
                "Shop", """
                        package demo;

                        import java.util.ArrayList;
                        import java.util.List;

                        public class Shop {
                            public static void main(String[] args) {
                                List<String> items = new ArrayList<>();
                                items.add("x");
                                items.clear();
                                new Basket().pay();
                            }
                        }
                        """);

        // Решатель типов знает, что add() и clear() вызваны у списка, а не у корзины
        assertThat(names(checkResolved(new CheckUnusedMethodRule(), files)))
                .containsExactlyInAnyOrder("Basket.add", "Basket.clear");

        // Без него вызов засчитывается всем методам с таким именем
        Map<String, String> unresolved = new HashMap<>();
        files.forEach((name, code) -> unresolved.put(name + ".java", code));
        assertThat(RuleTests.checkProject(new CheckUnusedMethodRule(), unresolved)).isEmpty();
    }

    @Test
    void testDirectoryIsNotChecked() throws IOException {
        Path tests = Files.createDirectories(dir.resolve("src/test/java/demo"));
        Files.writeString(tests.resolve("OrderIT.java"), "package demo;\n\npublic class OrderIT extends BaseIT {}\n");
        Files.writeString(tests.resolve("BaseIT.java"), "package demo;\n\npublic class BaseIT { void prepare() {} }\n");

        List<SourceFile> sources = new FileSystemSourceLoader().load(dir).sources();

        assertThat(new CheckUnusedClassRule().checkProject(sources)).isEmpty();
        assertThat(new CheckUnusedMethodRule().checkProject(sources)).isEmpty();
    }

    /**
     * @param files имя класса -> исходный код; файлы кладутся в каталог пакета demo и загружаются с решателем типов
     */
    private List<Violation> checkResolved(ProjectRule rule, Map<String, String> files) throws IOException {
        Path packageDirectory = Files.createDirectories(dir.resolve("demo"));
        for (Map.Entry<String, String> file : files.entrySet()) {
            Files.writeString(packageDirectory.resolve(file.getKey() + ".java"), file.getValue());
        }
        return rule.checkProject(new FileSystemSourceLoader().load(dir).sources());
    }

    // Имя класса или метода из сообщения: оно стоит там первым в кавычках
    private List<String> names(List<Violation> violations) {
        return violations.stream()
                .map(violation -> {
                    Matcher matcher = QUOTED_NAME.matcher(violation.message());
                    return matcher.find() ? matcher.group(1) : violation.message();
                })
                .toList();
    }
}
