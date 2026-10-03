package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.TryStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckUnclosedResourceRule extends AbstractRule {
    private static final String CLOSE = "close";
    private static final String FILES = "Files";
    private static final Set<String> FILES_STREAM_METHODS = Set.of("lines", "list", "walk", "find");

    // Завершающие операции стрима: после них закрыть стрим уже некому
    private static final Set<String> TERMINAL_OPERATIONS = Set.of(
            "collect", "forEach", "forEachOrdered", "count", "toList", "toArray", "reduce", "anyMatch", "allMatch",
            "noneMatch", "findFirst", "findAny", "min", "max", "sum");

    @Override
    public String code() {
        return RuleCodes.CHECK_UNCLOSED_RESOURCE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет ресурсы (потоки, соединения, стримы файлов), которые открыты и не закрыты";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();

        for (VariableDeclarator variable : sourceFile.unit().findAll(VariableDeclarator.class)) {
            if (Resources.isLocalVariable(variable)
                    && variable.getInitializer().filter(Resources::opens).isPresent()
                    && isNeverClosed(variable)) {
                violations.add(violation(sourceFile, variable,
                        "Ресурс '" + variable.getNameAsString() + "' открыт и не закрыт:"
                                + " используйте try-with-resources"));
            }
        }

        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (isFilesStream(call) && isConsumedWithoutClosing(call)) {
                violations.add(violation(sourceFile, call,
                        "Стрим Files." + call.getNameAsString() + "(...) держит открытый файл и не закрыт:"
                                + " используйте try-with-resources"));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.RESOURCE;
    }

    private boolean isNeverClosed(VariableDeclarator variable) {
        Optional<Node> callable = Nodes.enclosingCallable(variable);
        if (callable.isEmpty()) {
            return false;
        }

        for (NameExpr usage : callable.get().findAll(NameExpr.class)) {
            if (!usage.getNameAsString().equals(variable.getNameAsString())) {
                continue;
            }

            Node parent = usage.getParentNode().orElse(null);
            boolean isCallOnResource = parent instanceof MethodCallExpr call
                    && call.getScope().filter(scope -> scope == usage).isPresent();

            // Ресурс возвращается, передается дальше, оборачивается или указан в try (resource): закроют там
            if (!isCallOnResource) {
                return false;
            }
            if (CLOSE.equals(((MethodCallExpr) parent).getNameAsString())) {
                return false;
            }
        }
        return true;
    }

    private boolean isFilesStream(MethodCallExpr call) {
        return FILES_STREAM_METHODS.contains(call.getNameAsString())
                && MethodCalls.isCallOn(call, FILES, call.getNameAsString());
    }

    // Files.lines(path).filter(...).collect(...): цепочка закончилась, а стрим так и не закрыли
    private boolean isConsumedWithoutClosing(MethodCallExpr call) {
        MethodCallExpr top = call;
        while (true) {
            if (isTryResource(top)) {
                return false;
            }

            MethodCallExpr current = top;
            Optional<MethodCallExpr> next = top.getParentNode()
                    .filter(parent -> parent instanceof MethodCallExpr)
                    .map(parent -> (MethodCallExpr) parent)
                    .filter(parent -> parent.getScope().filter(scope -> scope == current).isPresent());
            if (next.isEmpty()) {
                break;
            }
            top = next.get();
        }
        return top != call && TERMINAL_OPERATIONS.contains(top.getNameAsString());
    }

    private boolean isTryResource(Expression expression) {
        return expression.getParentNode()
                .filter(parent -> parent instanceof TryStmt)
                .map(parent -> (TryStmt) parent)
                .filter(tryStmt -> tryStmt.getResources().stream().anyMatch(resource -> resource == expression))
                .isPresent();
    }
}
