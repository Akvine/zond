package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.loaders.FileSystemSourceLoader;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.logical.PossibleNullDereferenceRule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ложные срабатывания анализа потока данных на значениях, связанных между собой: переменная не null тогда же,
 * когда не null другая переменная либо когда истинен флаг. Найдены прогоном по открытым проектам (zaproxy,
 * commons-lang, apollo, conductor, TheAlgorithms) и по рабочим проектам. В каждом тесте рядом с кодом, о
 * котором правило теперь молчит, стоит код, о котором оно по-прежнему обязано сообщить
 */
class CorrelatedValuesFalsePositivesTest {
    @TempDir
    Path dir;

    @Test
    void tailIsSetTogetherWithHead() {
        String code = """
                class Lists {
                    static class Node {
                        Node next;
                    }
                    Node build(int[] values) {
                        Node head = null;
                        Node tail = null;
                        for (int value : values) {
                            Node node = new Node();
                            if (head == null) {
                                head = node;
                            } else {
                                tail.next = node;
                            }
                            tail = node;
                        }
                        return head;
                    }
                    Node skipping(int[] values) {
                        Node head = null;
                        Node tail = null;
                        for (int value : values) {
                            Node node = new Node();
                            if (head == null) {
                                head = node;
                            } else {
                                tail.next = node;
                            }
                            if (value > 0) {
                                tail = node;
                            }
                        }
                        return head;
                    }
                }
                """;

        // В build хвост получает значение в каждой итерации, как и голова; в skipping - только для части элементов
        assertThat(RuleTests.lines(new PossibleNullDereferenceRule(), code)).containsExactly(27);
    }

    @Test
    void valueIsSetTogetherWithName() {
        String code = """
                import java.io.BufferedReader;
                import java.io.IOException;
                import java.util.ArrayList;
                import java.util.List;
                class Parser {
                    static List<String> headers(BufferedReader reader) throws IOException {
                        List<String> headers = new ArrayList<>();
                        String name = null;
                        StringBuilder value = null;
                        for (;;) {
                            String line = reader.readLine();
                            if (line == null || line.isEmpty()) {
                                break;
                            }
                            if (line.charAt(0) == ' ') {
                                if (value != null) {
                                    value.append(line.trim());
                                }
                            } else {
                                if (name != null) {
                                    headers.add(name + value.toString());
                                }
                                int colon = line.indexOf(':');
                                if (colon < 0) {
                                    name = null;
                                    value = null;
                                } else {
                                    name = line.substring(0, colon).trim();
                                    value = new StringBuilder(line.substring(colon + 1).trim());
                                }
                            }
                        }
                        if (name != null) {
                            headers.add(name + value.toString());
                        }
                        return headers;
                    }
                    static List<String> broken(BufferedReader reader) throws IOException {
                        List<String> headers = new ArrayList<>();
                        String name = null;
                        StringBuilder value = null;
                        for (;;) {
                            String line = reader.readLine();
                            if (line == null || line.isEmpty()) {
                                break;
                            }
                            int colon = line.indexOf(':');
                            if (colon < 0) {
                                value = null;
                            } else {
                                name = line.substring(0, colon).trim();
                                value = new StringBuilder(line.substring(colon + 1).trim());
                            }
                        }
                        if (name != null) {
                            headers.add(name + value.toString());
                        }
                        return headers;
                    }
                }
                """;

        // В headers имя и значение сбрасываются и задаются только вместе; в broken значение сбрасывается одно
        assertThat(RuleTests.lines(new PossibleNullDereferenceRule(), code)).containsExactly(56);
    }

    @Test
    void flagIsComputedOnlyWhereVariableIsSet() {
        String code = """
                import java.util.List;
                class Variant {
                    void params(List<String> path, String actual) {
                        boolean useActual = false;
                        String[] parts = null;
                        int position = 0;
                        for (String node : path) {
                            if (parts == null && actual != null) {
                                parts = actual.split("/");
                                useActual = path.size() == parts.length;
                            }
                            String value = useActual ? parts[position] : node;
                            position++;
                        }
                    }
                    void unrelated(List<String> path, String actual) {
                        boolean useActual = path.size() > 2;
                        String[] parts = null;
                        int position = 0;
                        for (String node : path) {
                            if (parts == null && actual != null) {
                                parts = actual.split("/");
                            }
                            String value = useActual ? parts[position] : node;
                            position++;
                        }
                    }
                }
                """;

        // Во втором методе флаг вычислен отдельно от parts и ничего о нем не говорит
        assertThat(RuleTests.lines(new PossibleNullDereferenceRule(), code)).containsExactly(24);
    }

    @Test
    void flagSwitchedTogetherWithBuffer() {
        String code = """
                import java.util.ArrayList;
                import java.util.List;
                class Lexer {
                    List<StringBuilder> lex(String format) {
                        List<StringBuilder> list = new ArrayList<>();
                        boolean inLiteral = false;
                        StringBuilder buffer = null;
                        for (int i = 0; i < format.length(); i++) {
                            char ch = format.charAt(i);
                            if (inLiteral && ch != '\\'') {
                                buffer.append(ch);
                                continue;
                            }
                            switch (ch) {
                                case '\\'':
                                    if (inLiteral) {
                                        buffer = null;
                                        inLiteral = false;
                                    } else {
                                        buffer = new StringBuilder();
                                        list.add(buffer);
                                        inLiteral = true;
                                    }
                                    break;
                                default:
                                    list.add(new StringBuilder().append(ch));
                            }
                        }
                        return list;
                    }
                    List<StringBuilder> broken(String format) {
                        List<StringBuilder> list = new ArrayList<>();
                        boolean inLiteral = false;
                        StringBuilder buffer = null;
                        for (int i = 0; i < format.length(); i++) {
                            char ch = format.charAt(i);
                            if (ch != '\\'') {
                                buffer.append(ch);
                                continue;
                            }
                            if (inLiteral) {
                                buffer = null;
                                inLiteral = false;
                            } else {
                                buffer = new StringBuilder();
                                inLiteral = true;
                            }
                        }
                        return list;
                    }
                }
                """;

        // В broken обращение к buffer флагом не закрыто: до первой кавычки он null
        assertThat(RuleTests.lines(new PossibleNullDereferenceRule(), code)).containsExactly(38);
    }

    @Test
    void valueCreatedAtTryStartUnderSameCondition() {
        String code = """
                class Profiler {
                    private boolean measured;
                    void run(Runnable task) {
                        StopWatch timer = null;
                        try {
                            if (measured) {
                                timer = new StopWatch();
                                timer.start();
                            }
                            task.run();
                        } finally {
                            if (measured && timer.isRunning()) {
                                timer.stop();
                            }
                        }
                    }
                    void late(Runnable task) {
                        StopWatch timer = null;
                        try {
                            task.run();
                            if (measured) {
                                timer = new StopWatch();
                            }
                        } finally {
                            if (measured && timer.isRunning()) {
                                timer.stop();
                            }
                        }
                    }
                }
                """;

        // В late до создания таймера выполняется task.run(): исключение из него приведет в finally с timer == null
        assertThat(RuleTests.lines(new PossibleNullDereferenceRule(), code)).containsExactly(25);
    }

    @Test
    void ownAssertionWithConditionInArgument() {
        String code = """
                class Controller {
                    void create(String appId, NamespaceDto dto) {
                        RequestPrecondition.checkArguments(dto != null, "payload can not be empty");
                        dto.setAppId(appId);
                    }
                    String decode(JsonNode data) {
                        requireResponse(data != null && data.isObject(), "invalid response");
                        return data.path("answers").asText();
                    }
                    void junit(User user) {
                        assertTrue("user is expected", user != null);
                        user.getName();
                    }
                    void audited(String appId, NamespaceDto dto) {
                        Audit.record(dto != null, "payload");
                        dto.setAppId(appId);
                    }
                    void inverted(User user) {
                        assertFalse(user != null);
                        user.getName();
                    }
                }
                """;

        // record - не проверка, а assertFalse утверждает обратное
        assertThat(RuleTests.lines(new PossibleNullDereferenceRule(), code)).containsExactly(16, 20);
    }

    @Test
    void jacksonReportAlwaysThrows() {
        String code = """
                class AnyDeserializer {
                    String type(JsonNode root, DeserializationContext ctxt) throws Exception {
                        JsonNode type = root.get("@type");
                        if (type == null || !type.isTextual()) {
                            ctxt.reportBadDefinition(Object.class, "invalid '@type' field");
                        }
                        return type.textValue();
                    }
                    String logged(JsonNode root, Logger log) {
                        JsonNode type = root.get("@type");
                        if (type == null || !type.isTextual()) {
                            log.warn("invalid '@type' field");
                        }
                        return type.textValue();
                    }
                }
                """;

        assertThat(RuleTests.lines(new PossibleNullDereferenceRule(), code)).containsExactly(14);
    }

    @Test
    void sameCallChainWasCheckedAbove() throws IOException {
        String code = """
                package audit;
                import java.util.List;
                class Audit {
                    static class Span {
                        String id;
                    }
                    static class Tracer {
                        List<Span> spans;
                        Span getActiveSpan() {
                            return spans.isEmpty() ? null : spans.get(0);
                        }
                    }
                    static class TraceContext {
                        Tracer current;
                        Tracer tracer() {
                            return current;
                        }
                    }
                    String checked(TraceContext context) {
                        if (context.tracer().getActiveSpan() == null) {
                            return "";
                        }
                        return context.tracer().getActiveSpan().id;
                    }
                    String unchecked(TraceContext context) {
                        return context.tracer().getActiveSpan().id;
                    }
                }
                """;
        Files.createDirectories(dir.resolve("audit"));
        Files.writeString(dir.resolve("audit/Audit.java"), code);
        List<SourceFile> sources = new FileSystemSourceLoader().load(dir).sources();

        // Вызов tracer() между проверкой и обращением не отменяет проверку той же цепочки
        assertThat(new PossibleNullDereferenceRule().checkProject(sources)).extracting(Violation::line)
                .containsExactly(26);
    }

    @Test
    void nullPointerIsCaughtOnPurpose() {
        String code = """
                import java.util.List;
                class Header {
                    private List<String> values;
                    String getHeader(String name) {
                        return values.isEmpty() ? null : values.get(0);
                    }
                    boolean isClose() {
                        try {
                            if (getHeader("Connection").equalsIgnoreCase("close")) {
                                return true;
                            }
                        } catch (NullPointerException e) {
                            // заголовка нет
                        }
                        return getHeader("Proxy-Connection").equalsIgnoreCase("close");
                    }
                }
                """;

        assertThat(RuleTests.lines(new PossibleNullDereferenceRule(), code)).containsExactly(15);
    }

    @Test
    void ownEmptinessCheckThroughOtherMethods() {
        String code = """
                import java.lang.reflect.Array;
                import java.util.Arrays;
                class Arrays2 {
                    static boolean isNotEmpty(int[] array) {
                        return !isEmpty(array);
                    }
                    static boolean isEmpty(int[] array) {
                        return getLength(array) == 0;
                    }
                    static int getLength(Object array) {
                        return array == null ? 0 : Array.getLength(array);
                    }
                    static int[] sorted(int[] array) {
                        if (array != null) {
                            Arrays.sort(array);
                        }
                        return array;
                    }
                    static int last(int[] given) {
                        int[] indices = sorted(given);
                        if (isNotEmpty(indices)) {
                            return indices.length;
                        }
                        return 0;
                    }
                    static int raw(int[] given) {
                        int[] indices = sorted(given);
                        return indices.length;
                    }
                }
                """;

        assertThat(RuleTests.lines(new PossibleNullDereferenceRule(), code)).containsExactly(28);
    }
}
