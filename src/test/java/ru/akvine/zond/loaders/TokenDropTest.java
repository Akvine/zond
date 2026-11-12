package ru.akvine.zond.loaders;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * После разбора дерево не держит лексемы файла, а строки узлов и комментарии остаются на месте
 */
class TokenDropTest {

    @Test
    void treeKeepsLinesAndCommentsWithoutTokens() {
        CompilationUnit unit = StaticJavaParser.parse("""
                // Лицензия
                class Sample {
                    // считает сумму
                    int sum(int a, int b) {
                        return a + b;
                    }
                }
                """);

        FileSystemSourceLoader.dropTokens(unit);

        MethodDeclaration method = unit.findFirst(MethodDeclaration.class).orElseThrow();
        assertThat(unit.findAll(Node.class).stream().noneMatch(node -> node.getTokenRange().isPresent())).isTrue();
        assertThat(unit.getAllContainedComments().stream().noneMatch(node -> node.getTokenRange().isPresent())).isTrue();
        assertThat(method.getBegin().orElseThrow().line).isEqualTo(4);
        assertThat(method.getComment().orElseThrow().getContent().trim()).isEqualTo("считает сумму");
        assertThat(method.getComment().orElseThrow().getBegin().orElseThrow().line).isEqualTo(3);
    }
}
