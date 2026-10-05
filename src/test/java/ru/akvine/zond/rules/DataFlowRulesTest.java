package ru.akvine.zond.rules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.loaders.FileSystemSourceLoader;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.UnreachableCodeRule;
import ru.akvine.zond.rules.logical.AlwaysNullDereferenceRule;
import ru.akvine.zond.rules.logical.ConstantConditionRule;
import ru.akvine.zond.rules.logical.DivisionByZeroRule;
import ru.akvine.zond.rules.logical.IndexOutOfBoundsRule;
import ru.akvine.zond.rules.logical.NullArgumentRule;
import ru.akvine.zond.rules.logical.PossibleNullDereferenceRule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:323 - jr:329: значения переменных и полей прослеживаются по ходу метода, через циклы и вызовы.
 * Строка, на которой ожидается находка, помечена в проверяемом коде комментарием: // @NULL, // @MAYBE и так далее.
 * Методы без пометок - случаи, в которых находки быть не должно.
 */
class DataFlowRulesTest {
    private static final String PACKAGE = "demo";
    private static final String JAVA_EXTENSION = ".java";

    private static final String SAMPLE = """
            package demo;

            import java.util.List;
            import java.util.Objects;
            import java.util.Optional;

            public class Sample {
                private String field;

                int alwaysNull() {
                    String text = null;
                    return text.length(); // @NULL
                }

                int nullBranch(String text) {
                    if (text == null) {
                        return text.length(); // @NULL
                    }
                    return text.length();
                }

                int assignedInOneBranch(boolean flag) {
                    String text = null;
                    if (flag) {
                        text = "a";
                    }
                    return text.length(); // @MAYBE
                }

                int checkedButUsed(String text) {
                    if (text == null) {
                        System.out.println("empty");
                    }
                    return text.length(); // @MAYBE
                }

                int afterCatch() {
                    String text = null;
                    try {
                        text = load();
                    } catch (RuntimeException exception) {
                        System.out.println("failed");
                    }
                    return text.length(); // @MAYBE
                }

                int orElseNull(Optional<String> value) {
                    String text = value.orElse(null);
                    return text.length(); // @MAYBE
                }

                int fromMethod(int id) {
                    return find(id).length(); // @MAYBE
                }

                int throughChain(int id) {
                    String found = wrap(id);
                    return found.length(); // @MAYBE
                }

                private String wrap(int id) {
                    return find(id);
                }

                private String find(int id) {
                    if (id < 0) {
                        return null;
                    }
                    return field;
                }

                private String load() {
                    return field;
                }

                int guardedBySameCondition(boolean flag) {
                    String text = null;
                    if (flag) {
                        text = "a";
                    }
                    if (flag) {
                        return text.length();
                    }
                    return 0;
                }

                int guardedByFlag(List<String> items) {
                    String text = null;
                    boolean found = false;
                    if (!items.isEmpty()) {
                        text = items.get(0);
                        found = true;
                    }
                    if (!found) {
                        return 0;
                    }
                    return text.length();
                }

                int assignedInLoop(List<String> items) {
                    String last = null;
                    for (String item : items) {
                        last = item;
                    }
                    return last == null ? 0 : last.length();
                }

                int earlyReturn(String text) {
                    if (text == null) {
                        return 0;
                    }
                    return text.length();
                }

                int checkedByLibrary(boolean flag) {
                    String text = flag ? "a" : null;
                    Objects.requireNonNull(text);
                    return text.length();
                }

                int checkedByOwnMethod(boolean flag) {
                    String text = flag ? "a" : null;
                    if (isBlank(text)) {
                        return 0;
                    }
                    return text.length();
                }

                private static boolean isBlank(String value) {
                    return value == null || value.trim().isEmpty();
                }

                int checkedByOwnAssertion(String text) {
                    ensure(text);
                    if (text == null) { // @CONST
                        return 0;
                    }
                    return text.length();
                }

                private static void ensure(Object value) {
                    if (value == null) {
                        throw new IllegalArgumentException("value");
                    }
                }

                int checkedCall() {
                    return find(1) == null ? 0 : find(1).length();
                }

                int checkedByFlagVariable(int id) {
                    String text = find(id);
                    boolean isPresent = text != null && !text.isEmpty();
                    if (!isPresent) {
                        return 0;
                    }
                    return text.length();
                }

                int reportedOnce(int id) {
                    int length = find(id).length(); // @MAYBE
                    return length + find(id).length();
                }

                int neverAssignedIfLoopIsEmpty(List<String> items) {
                    String last = null;
                    for (String item : items) {
                        last = item;
                    }
                    return last.length(); // @MAYBE
                }

                int assignedBeforeLoop(List<String> items) {
                    String last = "";
                    for (String item : items) {
                        last = item;
                    }
                    return last.length();
                }

                int loopUntilFound() {
                    String value = null;
                    while (value == null) {
                        value = load();
                    }
                    return value.length();
                }

                int counterAfterLoop() {
                    int i = 0;
                    while (i < 10) {
                        i++;
                    }
                    if (i >= 10) { // @CONST
                        return i;
                    }
                    return 0;
                }

                int nullOnSecondIteration(List<String> items) {
                    String previous = "";
                    int total = 0;
                    for (String item : items) {
                        total += previous.length(); // @MAYBE
                        previous = item.isEmpty() ? null : item;
                    }
                    return total;
                }

                int passNull() {
                    return size(null); // @ARG
                }

                int passMaybeNull(boolean flag) {
                    String text = flag ? "a" : null;
                    return size(text); // @ARG
                }

                private int size(String text) {
                    return text.length();
                }

                int passNullToSafeMethod() {
                    return safeSize(null);
                }

                private int safeSize(String text) {
                    return text == null ? 0 : text.length();
                }

                int constants(String text, int count) {
                    int length = text.length();
                    if (text != null) { // @CONST
                        length++;
                    }
                    if (count > 10) {
                        if (count > 5) { // @CONST
                            length++;
                        }
                    }
                    String created = new String("a");
                    if (created == null) { // @CONST
                        length--;
                    }
                    return length;
                }

                int noConstants(List<String> items) {
                    int count = 0;
                    for (String item : items) {
                        if (count > 3) {
                            break;
                        }
                        count++;
                    }
                    boolean debug = false;
                    if (debug) {
                        count++;
                    }
                    while (count < 10) {
                        count += 2;
                    }
                    Integer boxed = 1;
                    boxed = items.isEmpty() ? null : 2;
                    if (boxed == null) {
                        count++;
                    }
                    return count;
                }

                int unreachable(String text) {
                    int length = text.length();
                    if (text != null) { // @CONST
                        return length;
                    }
                    System.out.println("never"); // @DEAD
                    return 0;
                }

                int afterFail(String text) {
                    if (text == null) {
                        fail();
                        System.out.println("never"); // @DEAD
                    }
                    return text.length();
                }

                String placeholderAfterFail(String text) {
                    if (text == null) {
                        fail();
                        return null;
                    }
                    return text;
                }

                private static void fail() {
                    throw new IllegalStateException("fail");
                }

                int divide(int total) {
                    int count = 0;
                    return total / count; // @ZERO
                }

                int divideChecked(int total, int count) {
                    if (count == 0) {
                        return 0;
                    }
                    return total / count;
                }

                int index(String text) {
                    int[] numbers = new int[3];
                    numbers[3] = 1; // @INDEX
                    for (int i = 0; i <= numbers.length; i++) {
                        numbers[i] = i; // @INDEX
                    }
                    for (int i = 0; i < numbers.length; i++) {
                        numbers[i] = i;
                    }
                    int colon = text.indexOf(':');
                    String tail = text.substring(colon); // @INDEX
                    int dot = text.indexOf('.');
                    if (dot < 0) {
                        return 0;
                    }
                    return text.substring(dot + 1).length() + text.charAt(dot) + tail.length() + numbers[2];
                }

                String guardedSearch(String text) {
                    if (text.contains(":")) {
                        return text.substring(text.indexOf(':'));
                    }
                    return text.substring(text.indexOf('/') + 1);
                }
            }
            """;

    private static final String REPOSITORY = """
            package demo;

            public class Repository {
                public String findName(int id) {
                    if (id < 0) {
                        return null;
                    }
                    return "name";
                }
            }
            """;

    private static final String SERVICE = """
            package demo;

            public class Service {
                private Repository repository;
                private Base base;

                int nameLength(int id) {
                    return repository.findName(id).length(); // @MAYBE
                }

                int overridable() {
                    return base.name().length();
                }
            }
            """;

    // Поля своего объекта и аннотации
    private static final String HOLDER = """
            package demo;

            import java.util.ArrayList;
            import java.util.List;

            public class Holder {
                private String name;
                private final List<String> items = new ArrayList<>();
                @Nullable
                private String comment;
                private volatile String shared;
                private Finder finder;

                int checkedButUsed() {
                    if (name == null) {
                        System.out.println("no name");
                    }
                    return name.length(); // @MAYBE
                }

                int checkedThenUsed() {
                    if (name == null) {
                        return 0;
                    }
                    return name.length();
                }

                int changedByOwnMethod() {
                    if (name == null) {
                        init();
                    }
                    return name.length();
                }

                private void init() {
                    name = "x";
                }

                int keptByMethodWithoutSideEffects() {
                    if (this.name == null) {
                        log();
                    }
                    return this.name.length(); // @MAYBE
                }

                private void log() {
                    System.out.println("log");
                }

                int finalField() {
                    if (items != null) { // @CONST
                        return items.size();
                    }
                    return 0;
                }

                int nullableField() {
                    return comment.length(); // @MAYBE
                }

                int nullableFieldChecked() {
                    return comment == null ? 0 : comment.length();
                }

                int volatileField() {
                    if (shared == null) {
                        System.out.println("empty");
                    }
                    return shared.length();
                }

                String derivedGetter() {
                    if (name == null) {
                        return null;
                    }
                    return name.trim();
                }

                int usesDerivedGetter() {
                    return derivedGetter().length();
                }

                @Nullable
                String annotated() {
                    return name;
                }

                int usesAnnotated() {
                    return annotated().length(); // @MAYBE
                }

                int usesAnnotatedInterface() {
                    return finder.lookup(1).length(); // @MAYBE
                }

                int nonNullParameter(@NonNull String text) {
                    if (text == null) { // @CONST
                        return 0;
                    }
                    return text.length();
                }
            }
            """;

    private static final String FINDER = """
            package demo;

            public interface Finder {
                @Nullable
                String lookup(int id);
            }
            """;

    // Метод переопределен: какое тело выполнится при вызове, неизвестно, и сводка не используется
    private static final String BASE = """
            package demo;

            public class Base {
                String name() {
                    return null;
                }
            }
            """;

    private static final String CHILD = """
            package demo;

            public class Child extends Base {
                @Override
                String name() {
                    return "child";
                }
            }
            """;

    @TempDir
    Path dir;

    private final Map<String, String> files = new LinkedHashMap<>();
    private List<SourceFile> sources;

    @BeforeEach
    void setUp() throws IOException {
        files.put("Sample", SAMPLE);
        files.put("Repository", REPOSITORY);
        files.put("Service", SERVICE);
        files.put("Base", BASE);
        files.put("Child", CHILD);
        files.put("Holder", HOLDER);
        files.put("Finder", FINDER);
        Path packageDirectory = Files.createDirectories(dir.resolve(PACKAGE));
        for (Map.Entry<String, String> file : files.entrySet()) {
            Files.writeString(packageDirectory.resolve(file.getKey() + JAVA_EXTENSION), file.getValue());
        }
        sources = new FileSystemSourceLoader().load(dir).sources();
    }

    @Test
    void alwaysNullDereference() {
        assertThat(found(new AlwaysNullDereferenceRule())).isEqualTo(marked("@NULL"));
    }

    @Test
    void possibleNullDereference() {
        assertThat(found(new PossibleNullDereferenceRule())).isEqualTo(marked("@MAYBE"));
    }

    @Test
    void nullArgument() {
        assertThat(found(new NullArgumentRule())).isEqualTo(marked("@ARG"));
    }

    @Test
    void constantCondition() {
        assertThat(found(new ConstantConditionRule())).isEqualTo(marked("@CONST"));
    }

    @Test
    void unreachableCode() {
        assertThat(found(new UnreachableCodeRule())).isEqualTo(marked("@DEAD"));
    }

    @Test
    void divisionByZero() {
        assertThat(found(new DivisionByZeroRule())).isEqualTo(marked("@ZERO"));
    }

    @Test
    void indexOutOfBounds() {
        assertThat(found(new IndexOutOfBoundsRule())).isEqualTo(marked("@INDEX"));
    }

    @Test
    void messagesExplainWhereValueComesFrom() {
        assertThat(new PossibleNullDereferenceRule().checkProject(sources)).extracting(Violation::message)
                .anyMatch(message -> message.contains("метод 'find' может вернуть null"))
                .anyMatch(message -> message.contains("переменной присвоен null на строке"))
                .anyMatch(message -> message.contains("orElse(null)"))
                .anyMatch(message -> message.contains("поле помечено @Nullable"))
                .anyMatch(message -> message.contains("метод 'lookup' помечен @Nullable"));
        assertThat(new UnreachableCodeRule().checkProject(sources)).extracting(Violation::message)
                .anyMatch(message -> message.contains("метод 'fail'"))
                .anyMatch(message -> message.contains("условие 'text != null'"));
    }

    // Класс:строка, в порядке следования
    private List<String> found(ProjectRule rule) {
        return rule.checkProject(sources).stream()
                .map(violation -> violation.file().getFileName().toString().replace(JAVA_EXTENSION, "")
                        + ":" + violation.line())
                .distinct()
                .sorted()
                .toList();
    }

    private List<String> marked(String marker) {
        List<String> places = new ArrayList<>();
        files.forEach((name, code) -> {
            String[] lines = code.split("\n");
            for (int index = 0; index < lines.length; index++) {
                if (lines[index].contains("// " + marker)) {
                    places.add(name + ":" + (index + 1));
                }
            }
        });
        return places.stream().sorted().toList();
    }
}
