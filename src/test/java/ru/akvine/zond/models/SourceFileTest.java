package ru.akvine.zond.models;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Узлы файла по типам запоминаются: ответ обязан совпадать с обходом дерева заново
 */
class SourceFileTest {
    private static final String CODE = """
            class Sample {
                private final java.util.List<String> names = new java.util.ArrayList<>();

                void first(String name) {
                    names.add(name.trim());
                    print(names.size());
                }

                int second() {
                    return names.stream().mapToInt(String::length).sum();
                }

                void print(int value) {
                    System.out.println(value);
                }
            }
            """;

    @Test
    void findsSameNodesAsTreeWalk() {
        CompilationUnit unit = StaticJavaParser.parse(CODE);
        SourceFile file = new SourceFile(Path.of("Sample.java"), unit);

        // Те же самые узлы и в том же порядке - и для конечного типа, и для общего предка
        assertSame(file.findAll(MethodCallExpr.class), unit.findAll(MethodCallExpr.class));
        assertSame(file.findAll(MethodDeclaration.class), unit.findAll(MethodDeclaration.class));
        assertSame(file.findAll(Expression.class), unit.findAll(Expression.class));
        assertSame(file.findAll(Node.class), unit.findAll(Node.class));
        assertThat(file.findAll(MethodCallExpr.class)).hasSize(8);
    }

    @Test
    void returnsListThatCallerMayChange() {
        SourceFile file = new SourceFile(Path.of("Sample.java"), StaticJavaParser.parse(CODE));

        List<MethodDeclaration> methods = file.findAll(MethodDeclaration.class);
        methods.remove(0);

        // Правило вправе менять свой список: на ответ другому правилу это не влияет
        assertThat(methods).hasSize(2);
        assertThat(file.findAll(MethodDeclaration.class)).hasSize(3);
    }

    @Test
    void filtersNodesByCondition() {
        CompilationUnit unit = StaticJavaParser.parse(CODE);
        SourceFile file = new SourceFile(Path.of("Sample.java"), unit);

        List<MethodCallExpr> found = file.findAll(MethodCallExpr.class, call -> call.getArguments().isEmpty());

        assertSame(found, unit.findAll(MethodCallExpr.class, call -> call.getArguments().isEmpty()));
        assertThat(found).isNotEmpty();
    }

    @Test
    void printsTextOnce() {
        CompilationUnit unit = StaticJavaParser.parse(CODE);
        SourceFile file = new SourceFile(Path.of("Sample.java"), unit);

        assertThat(file.text()).isEqualTo(unit.toString());
        // Второй раз файл не печатается: возвращается та же строка
        assertThat(file.text() == file.text()).isTrue();
    }

    // Узлы сравниваются как объекты: два одинаковых по тексту вызова - разные узлы
    private <N extends Node> void assertSame(List<N> actual, List<N> expected) {
        assertThat(actual.size()).isEqualTo(expected.size());
        for (int index = 0; index < expected.size(); index++) {
            assertThat(actual.get(index) == expected.get(index)).isTrue();
        }
    }
}
