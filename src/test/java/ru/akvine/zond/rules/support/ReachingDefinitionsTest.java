package ru.akvine.zond.rules.support;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.NameExpr;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * До чтения переменной доходят только те присваивания, что могут выполниться перед ним
 */
class ReachingDefinitionsTest {

    @Test
    void overwrittenAndBranchAssignments() {
        // Значения в коде помечены строками: по ним видно, какое присваивание дошло
        assertThat(reaching("""
                void run(String param, boolean flag) {
                    String bar = param;
                    bar = "safe";
                    use(bar);
                }
                """, "use")).containsExactly("\"safe\"");
        assertThat(reaching("""
                void run(String param, boolean flag) {
                    String bar = "initial";
                    if (flag) {
                        bar = param;
                    }
                    use(bar);
                }
                """, "use")).containsExactlyInAnyOrder("\"initial\"", "param");
        assertThat(reaching("""
                void run(String param) {
                    String bar = "initial";
                    if (param == null) {
                        bar = "early";
                        return;
                    }
                    use(bar);
                }
                """, "use")).containsExactly("\"initial\"");
    }

    @Test
    void constantBranchesAndSwitch() {
        assertThat(reaching("""
                void run(String param) {
                    int num = 106;
                    String bar;
                    if ((7 * 18) + num > 200) bar = "always"; else bar = param;
                    use(bar);
                }
                """, "use")).containsExactly("\"always\"");
        assertThat(reaching("""
                void run(String param) {
                    String bar;
                    switch ("ABC".charAt(1)) {
                        case 'A':
                            bar = param;
                            break;
                        case 'B':
                            bar = "bob";
                            break;
                        default:
                            bar = param;
                    }
                    use(bar);
                }
                """, "use")).containsExactly("\"bob\"");
    }

    @Test
    void loopsAndTry() {
        assertThat(reaching("""
                void run(List<String> items, String param) {
                    String last = "none";
                    for (String item : items) {
                        use(last);
                        last = item;
                    }
                }
                """, "use")).containsExactlyInAnyOrder("\"none\"", "item");
        assertThat(reaching("""
                void run(String param) {
                    String value = "default";
                    try {
                        value = param;
                        parse(value);
                    } catch (Exception e) {
                        use(value);
                    }
                }
                """, "use")).containsExactlyInAnyOrder("\"default\"", "param");
    }

    // Определения, дошедшие до первого аргумента вызова call(...): значения присваиваний
    private List<String> reaching(String method, String call) {
        CompilationUnit unit = StaticJavaParser.parse("class Sample { " + method + " }");
        MethodDeclaration declaration = unit.findFirst(MethodDeclaration.class).orElseThrow();
        NameExpr usage = declaration.findAll(NameExpr.class).stream()
                .filter(name -> name.getParentNode()
                        .filter(parent -> parent.toString().startsWith(call + "("))
                        .isPresent())
                .findFirst()
                .orElseThrow();
        return ReachingDefinitions.of(declaration).definitionsOf(usage).orElseThrow().stream()
                .map(this::valueOf)
                .toList();
    }

    private String valueOf(Node definition) {
        if (definition instanceof AssignExpr assignment) {
            return assignment.getValue().toString();
        }
        if (definition instanceof VariableDeclarator variable) {
            return variable.getInitializer().map(Node::toString).orElse(variable.getNameAsString());
        }
        return definition.toString();
    }
}
