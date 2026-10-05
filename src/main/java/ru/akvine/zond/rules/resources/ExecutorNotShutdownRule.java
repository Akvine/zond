package ru.akvine.zond.rules.resources;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.ExecutorCreations;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Resources;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class ExecutorNotShutdownRule extends AbstractRule {
    private static final Set<String> SHUTDOWN_METHODS = Set.of("shutdown", "shutdownNow", "close");

    @Override
    public String code() {
        return RuleCodes.EXECUTOR_NOT_SHUTDOWN_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет пулы потоков, которые создаются и нигде не завершаются";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (VariableDeclarator variable : sourceFile.unit().findAll(VariableDeclarator.class)) {
            if (variable.getInitializer().filter(ExecutorCreations::creates).isEmpty()) {
                continue;
            }

            boolean isField = variable.getParentNode().filter(parent -> parent instanceof FieldDeclaration).isPresent();
            boolean neverShutdown = isField
                    ? !isShutdownAnywhere(sourceFile, variable.getNameAsString())
                    : Resources.isLocalVariable(variable) && isNeverShutdownLocally(variable);

            if (neverShutdown) {
                violations.add(violation(sourceFile, variable,
                        "Пул потоков '" + variable.getNameAsString() + "' создается через "
                                + ExecutorCreations.describe(variable.getInitializer().get()) + " и нигде не"
                                + " завершается: его потоки не дадут JVM остановиться и будут копиться при каждом"
                                + " создании; вызывайте shutdown() либо используйте try-with-resources"));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.RESOURCE;
    }

    // Поле: достаточно, чтобы пул завершали хоть где-то в файле, например в @PreDestroy
    private boolean isShutdownAnywhere(SourceFile sourceFile, String executor) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> SHUTDOWN_METHODS.contains(call.getNameAsString()))
                .anyMatch(call -> call.getScope()
                        .filter(scope -> MethodCalls.receiverName(scope).equals(executor))
                        .isPresent());
    }

    private boolean isNeverShutdownLocally(VariableDeclarator variable) {
        Optional<Node> callable = Nodes.enclosingCallable(variable);
        if (callable.isEmpty()) {
            return false;
        }

        for (NameExpr usage : callable.get().findAll(NameExpr.class)) {
            if (!usage.getNameAsString().equals(variable.getNameAsString())) {
                continue;
            }

            Node parent = usage.getParentNode().orElse(null);
            boolean isCallOnExecutor = parent instanceof MethodCallExpr call
                    && call.getScope().filter(scope -> scope == usage).isPresent();

            // Пул возвращается, передается дальше или сохраняется в поле: завершать его будут там
            if (!isCallOnExecutor || SHUTDOWN_METHODS.contains(((MethodCallExpr) parent).getNameAsString())) {
                return false;
            }
        }
        return true;
    }
}
