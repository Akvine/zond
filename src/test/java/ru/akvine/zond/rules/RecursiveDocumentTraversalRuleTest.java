package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.resources.RecursiveDocumentTraversalRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правило jr:424: рекурсивный обход и разбор документов XML, JSON и YAML
 */
class RecursiveDocumentTraversalRuleTest {
    private final RecursiveDocumentTraversalRule rule = new RecursiveDocumentTraversalRule();

    @Test
    void domTreeWalkedByRecursion() {
        String code = """
                import org.w3c.dom.Element;
                import org.w3c.dom.Node;
                import org.w3c.dom.NodeList;

                class XmlImporter {
                    void collect(Node node, List<String> values) {
                        if (node.getNodeType() == Node.TEXT_NODE) {
                            values.add(node.getTextContent());
                        }
                        NodeList children = node.getChildNodes();
                        for (int i = 0; i < children.getLength(); i++) {
                            collect(children.item(i), values);
                        }
                    }
                }
                """;

        List<Violation> violations = RuleTests.check(rule, code);
        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.line()).isEqualTo(6);
            assertThat(violation.message()).contains("'collect'", "XML", "StackOverflowError", "ArrayDeque");
        });
    }

    @Test
    void jsonTreeFlattenedInLambda() {
        String code = """
                class JsonFlattener {
                    void flatten(String prefix, JsonNode node, Map<String, String> result) {
                        if (node.isValueNode()) {
                            result.put(prefix, node.asText());
                            return;
                        }
                        node.fields().forEachRemaining(field -> flatten(prefix + "." + field.getKey(), field.getValue(), result));
                    }
                }
                """;

        assertThat(RuleTests.lines(rule, code)).containsExactly(2);
    }

    @Test
    void streamingParserWithMutualRecursion() {
        String code = """
                class EventReader {
                    Object readValue(JsonParser parser) throws IOException {
                        JsonToken token = parser.nextToken();
                        if (token == JsonToken.START_OBJECT) {
                            return readObject(parser);
                        }
                        if (token == JsonToken.START_ARRAY) {
                            return readArray(parser);
                        }
                        return parser.getText();
                    }
                    Map<String, Object> readObject(JsonParser parser) throws IOException {
                        Map<String, Object> result = new HashMap<>();
                        while (parser.nextToken() == JsonToken.FIELD_NAME) {
                            result.put(parser.getCurrentName(), readValue(parser));
                        }
                        return result;
                    }
                    List<Object> readArray(JsonParser parser) throws IOException {
                        List<Object> result = new ArrayList<>();
                        while (parser.nextToken() != JsonToken.END_ARRAY) {
                            result.add(readValue(parser));
                        }
                        return result;
                    }
                }
                """;

        // Об одном цикле разбора - одна находка, на его первом методе
        assertThat(RuleTests.check(rule, code)).singleElement().satisfies(violation -> {
            assertThat(violation.line()).isEqualTo(2);
            assertThat(violation.message()).contains("readValue -> readObject -> readValue");
        });
    }

    @Test
    void javaSyntaxTreeIsNotDocument() {
        // Node из дерева разбора Java: глубину задает исходный код, а не присланный файл
        String code = """
                import com.github.javaparser.ast.Node;
                import org.w3c.dom.*;

                class Visitor {
                    int count(Node node) {
                        int total = 1;
                        for (Node child : node.getChildNodes()) {
                            total += count(child);
                        }
                        return total;
                    }
                }
                """;

        assertThat(RuleTests.lines(rule, code)).isEmpty();
    }

    @Test
    void depthLimitAndExplicitStackAreSafe() {
        String code = """
                class Safe {
                    private static final int MAX_DEPTH = 64;
                    void walk(JsonNode node, int depth) {
                        if (depth > MAX_DEPTH) {
                            throw new IllegalArgumentException("Документ вложен слишком глубоко");
                        }
                        node.forEach(child -> walk(child, depth + 1));
                    }
                    void walkIteratively(JsonNode root) {
                        Deque<JsonNode> stack = new ArrayDeque<>();
                        stack.push(root);
                        while (!stack.isEmpty()) {
                            JsonNode node = stack.pop();
                            node.forEach(stack::push);
                        }
                    }
                    void print(JsonNode node, String path) {
                        print(node, path + "/");
                    }
                }
                """;

        // Тот же узел, переданный дальше, - не спуск по дереву
        assertThat(RuleTests.lines(rule, code)).isEmpty();
    }

    @Test
    void callWithoutDescentIsNotReported() {
        // Случаи из ThingsBoard: перегрузка с новым объектом, иерархия клиентов, поля класса
        String code = """
                import org.w3c.dom.Node;

                class Services {
                    public void validate(Node xml) throws Exception {
                        validate(new DOMSource(xml));
                    }
                    public void validate(Source source) throws Exception {
                        validator.validate(source);
                    }
                    JsonNode mergedTranslation(CustomerId customerId, JsonNode translation) {
                        JsonNode merged = merge(parentTranslation(customerId), translation);
                        Customer customer = customers.find(customerId);
                        return customer.isSubCustomer() ? mergedTranslation(customer.getParentId(), merged) : merged;
                    }
                    void walkInto(Class<?> type, JsonNode json, List<String> out) {
                        for (Field field : type.getDeclaredFields()) {
                            walkInto(field.getType(), json == null ? null : json.get(field.getName()), out);
                        }
                    }
                }
                """;

        assertThat(RuleTests.lines(rule, code)).isEmpty();
    }

    @Test
    void methodReferenceOverChildren() {
        String code = """
                class MetaClasses {
                    void update(JsonElement element) {
                        element.getAsJsonObject().entrySet().forEach(entry -> {
                            if (entry.getValue().isJsonArray()) {
                                entry.getValue().getAsJsonArray().forEach(this::update);
                            }
                        });
                    }
                }
                """;

        assertThat(RuleTests.lines(rule, code)).containsExactly(2);
    }

    @Test
    void indentIsNotDepthLimit() {
        String code = """
                class Printer {
                    void print(JSONObject object, int indent, StringBuilder out) {
                        for (String key : object.keySet()) {
                            Object value = object.get(key);
                            if (value instanceof JSONObject nested) {
                                print(nested, indent + 2, out);
                            }
                        }
                    }
                }
                """;

        assertThat(RuleTests.lines(rule, code)).containsExactly(2);
    }

    @Test
    void testsAreSkipped() {
        // Документ в тесте собран самим тестом; каталоги test правилу и не достаются - их пропускает сканер
        String code = """
                class JsonTreeTest {
                    @Test
                    void parsesTree() {
                        assertTree(JacksonUtil.toJsonNode("{}"));
                    }
                    void assertTree(JsonNode node) {
                        node.forEach(this::assertTree);
                    }
                }
                """;

        assertThat(RuleTests.lines(rule, code)).isEmpty();
    }
}
