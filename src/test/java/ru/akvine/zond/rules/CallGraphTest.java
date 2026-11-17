package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.loaders.FileSystemSourceLoader;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.rules.support.CallGraph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Граф вызовов: вызов разрешается, только если в проекте есть метод с таким именем и подходящим числом параметров.
 * Отсев по числу параметров не должен терять настоящие связи
 */
class CallGraphTest {
    @TempDir
    Path dir;

    @Test
    void linksCallsByNameAndParameterCount() throws IOException {
        Path packageDirectory = Files.createDirectories(dir.resolve("demo"));
        Files.writeString(packageDirectory.resolve("Registry.java"), """
                package demo;

                import java.util.ArrayList;
                import java.util.List;

                class Registry {
                    private final List<String> names = new ArrayList<>();

                    void run(String name) {
                        add(name, 1);
                        names.add(name);
                        log("added", name, names.size());
                        log("done");
                        missing(name);
                    }
                    void add(String name, int weight) {
                        names.add(name + weight);
                    }
                    void log(String text, Object... details) {
                        System.out.println(text + details.length);
                    }
                }
                """);
        List<SourceFile> sources = new FileSystemSourceLoader().load(dir).sources();
        CallGraph graph = CallGraph.of(sources);

        assertThat(targets(graph, sources, "add(name, 1)")).containsExactly("add");
        // У списка свой add с одним аргументом: метод проекта с двумя параметрами здесь ни при чем
        assertThat(targets(graph, sources, "names.add(name)")).isEmpty();
        // Метод с переменным числом параметров принимает любое число аргументов
        assertThat(targets(graph, sources, "log(\"added\", name, names.size())")).containsExactly("log");
        assertThat(targets(graph, sources, "log(\"done\")")).containsExactly("log");
        assertThat(targets(graph, sources, "missing(name)")).isEmpty();
    }

    private List<String> targets(CallGraph graph, List<SourceFile> sources, String call) {
        MethodCallExpr site = sources.stream()
                .flatMap(source -> source.findAll(MethodCallExpr.class).stream())
                .filter(found -> found.toString().equals(call))
                .findFirst()
                .orElseThrow(() -> new AssertionError("В коде нет вызова " + call));
        return graph.targetsOf(site).stream().map(MethodDeclaration::getNameAsString).toList();
    }
}
