package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.loaders.FileSystemSourceLoader;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.rules.support.LocalTypes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Типы там, где в коде их нет: параметр лямбды, переменная var цикла for-each, значение из библиотеки без classpath
 */
class ElementTypesTest {
    private static final String ORDER = """
            package demo;

            import java.util.List;
            import java.util.Map;

            @Getter
            public class Order {
                private List<Item> items;
                private Map<String, Item> itemsByCode;
                private boolean paid;
            }
            """;

    private static final String ITEM = """
            package demo;

            public class Item {
                String code;
                String code() { return code; }
            }
            """;

    @TempDir
    Path dir;

    @Test
    void knowsLambdaParameterByCollectionElement() throws IOException {
        List<SourceFile> sources = load(Map.of("Order", ORDER, "Item", ITEM, "Sample", """
                package demo;

                import java.util.List;
                import java.util.Map;
                import java.util.Optional;
                import java.util.concurrent.CompletableFuture;

                class Sample {
                    void run(List<String> names, Map<Integer, CompletableFuture<Item>> futures, Optional<Item> found) {
                        names.forEach(name -> use(name));
                        names.stream().filter(text -> !text.isEmpty()).forEach(kept -> use(kept));
                        futures.forEach((index, future) -> use(future));
                        futures.forEach((number, task) -> use(number));
                        futures.values().forEach(value -> use(value));
                        futures.keySet().stream().filter(key -> use(key));
                        found.ifPresent(present -> use(present));
                    }
                }
                """));

        // Решатель отдает такой тип как "? super String": в теле лямбды это String
        assertThat(typeOf(sources, "name")).contains("String");
        assertThat(typeOf(sources, "kept")).contains("String");
        assertThat(typeOf(sources, "future")).contains("CompletableFuture");
        assertThat(typeOf(sources, "number")).contains("Integer");
        assertThat(typeOf(sources, "value")).contains("CompletableFuture");
        assertThat(typeOf(sources, "key")).contains("Integer");
        assertThat(typeOf(sources, "present")).contains("Item");
    }

    @Test
    void knowsElementOfCollectionBehindLombokGetter() throws IOException {
        List<SourceFile> sources = load(Map.of("Order", ORDER, "Item", ITEM, "Sample", """
                package demo;

                import java.util.Comparator;
                import java.util.stream.Collectors;

                class Sample {
                    void run(Order order) {
                        order.getItems().forEach(item -> use(item));
                        order.getItems().stream().filter(candidate -> use(candidate)).count();
                        order.getItemsByCode().forEach((code, entry) -> use(entry));
                        order.getItems().stream().collect(Collectors.toMap(mapped -> use(mapped), mapped -> mapped));
                        order.getItems().stream().sorted(Comparator.comparing(sorted -> use(sorted)));
                        order.getItems().stream().map(Item::code).forEach(code -> use(code));
                        for (var each : order.getItems()) {
                            use(each);
                        }
                    }
                }
                """));

        // Геттера в исходниках нет, и решатель на такой цепочке сдается: тип элемента виден по полю List<Item> items
        assertThat(typeOf(sources, "item")).contains("Item");
        assertThat(typeOf(sources, "candidate")).contains("Item");
        assertThat(typeOf(sources, "entry")).contains("Item");
        assertThat(typeOf(sources, "mapped")).contains("Item");
        assertThat(typeOf(sources, "sorted")).contains("Item");
        assertThat(typeOf(sources, "each")).contains("Item");
        // После map(...) элемент уже другой: по объявлению коллекции его не узнать
        assertThat(typeOf(sources, "code").filter("Item"::equals)).isEmpty();
    }

    @Test
    void doesNotGuessElementOfWildcardAndTypeVariable() throws IOException {
        List<SourceFile> sources = load(Map.of("Item", ITEM, "Sample", """
                package demo;

                import java.util.List;

                class Sample<T> {
                    void run(List<? extends Item> readable, List<? super Item> writable, List<T> generic, List<?> any) {
                        readable.forEach(read -> use(read));
                        use(readable.get(0));
                        use(writable.get(0));
                        generic.forEach(element -> use(element));
                        any.forEach(unknown -> use(unknown));
                    }
                }
                """));

        assertThat(typeOf(sources, "read")).contains("Item");
        assertThat(typeOf(sources, "readable.get(0)")).contains("Item");
        // Из List<? super Item> достается Object, T - не имя класса, а про List<?> неизвестно ничего
        assertThat(typeOf(sources, "writable.get(0)").filter("Item"::equals)).isEmpty();
        assertThat(typeOf(sources, "element")).isEmpty();
        assertThat(typeOf(sources, "unknown").filter("Item"::equals)).isEmpty();
    }

    @Test
    void takesWrittenTypeNameWhenLibraryIsNotOnClasspath() throws IOException {
        List<SourceFile> sources = load(Map.of("Base", """
                package demo;

                import org.apache.http.client.HttpClient;

                class Base {
                    protected HttpClient shared;

                    HttpClient client() {
                        return shared;
                    }
                    <T> T find(Class<T> type) {
                        return null;
                    }
                }
                """, "Sample", """
                package demo;

                import com.fasterxml.jackson.core.JsonProcessingException;

                class Sample extends Base {
                    void run(Object failure) {
                        use(client());
                        use(shared);
                        use(find(String.class));
                        if (failure instanceof JsonProcessingException json) {
                            use(json);
                        }
                    }
                }
                """));

        // Что такое HttpClient, без библиотеки неизвестно, но получатель client().execute(...) - именно он
        assertThat(typeOf(sources, "client()")).contains("HttpClient");
        assertThat(typeOf(sources, "shared")).contains("HttpClient");
        assertThat(typeOf(sources, "json")).contains("JsonProcessingException");
        // <T> T find(...): T - не имя класса
        assertThat(typeOf(sources, "find(String.class)").filter("T"::equals)).isEmpty();
    }

    @Test
    void doesNotAskSolverInsideHugeBlock() throws IOException {
        // Решатель ищет имя, перебирая предыдущие операторы блока: на тысяче похожих строк это минуты
        String checks = "        use(holder.load());\n".repeat(301);
        List<SourceFile> sources = load(Map.of("Holder", """
                package demo;

                class Holder {
                    Item load() {
                        return new Item();
                    }
                }
                """, "Item", ITEM, "Sample", """
                package demo;

                class Sample {
                    void small(Holder holder) {
                        mark(holder.load());
                    }
                    void huge(Holder holder) {
                %s    }
                }
                """.formatted(checks)));

        assertThat(typeOf(sources, "mark", "holder.load()")).contains("Item");
        // Тип получателя по объявлению известен и здесь; результат вызова без решателя не определить
        assertThat(typeOf(sources, "use", "holder.load()")).isEmpty();
    }

    private List<SourceFile> load(Map<String, String> files) throws IOException {
        Path packageDirectory = Files.createDirectories(dir.resolve("demo"));
        for (Map.Entry<String, String> file : files.entrySet()) {
            Files.writeString(packageDirectory.resolve(file.getKey() + ".java"), file.getValue());
        }
        return new FileSystemSourceLoader().load(dir).sources();
    }

    private Optional<String> typeOf(List<SourceFile> sources, String text) {
        return typeOf(sources, "use", text);
    }

    // Тип выражения, переданного в use(...) или mark(...)
    private Optional<String> typeOf(List<SourceFile> sources, String method, String text) {
        Expression argument = sources.stream()
                .flatMap(source -> source.findAll(MethodCallExpr.class).stream())
                .filter(call -> call.getNameAsString().equals(method) && call.getArguments().size() == 1
                        && call.getArgument(0).toString().equals(text))
                .map(call -> call.getArgument(0))
                .findFirst()
                .orElseThrow(() -> new AssertionError("В коде нет " + method + "(" + text + ")"));
        return LocalTypes.typeOf(argument);
    }
}
