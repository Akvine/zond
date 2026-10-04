package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;

import java.util.List;
import java.util.Set;

@Component
public class CheckSystemExitRule extends AbstractRule {
    private static final String SYSTEM = "System";
    private static final String EXIT = "exit";
    private static final String MAIN = "main";
    private static final String GET_RUNTIME = "getRuntime";
    private static final Set<String> RUNTIME_EXIT_METHODS = Set.of("exit", "halt");

    @Override
    public String code() {
        return RuleCodes.CHECK_SYSTEM_EXIT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет System.exit() и Runtime.exit() вне метода main";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(this::isExit)
                .filter(call -> !isInsideMain(call))
                .map(call -> violation(sourceFile, call,
                        "'" + call + "' в прикладном коде: JVM остановится целиком, вместе со всеми потоками"
                                + " и незавершенными операциями; бросьте исключение и завершайте работу в main"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    // System.exit(...), Runtime.getRuntime().exit(...) / halt(...)
    private boolean isExit(MethodCallExpr call) {
        if (MethodCalls.isCallOn(call, SYSTEM, EXIT)) {
            return true;
        }
        return RUNTIME_EXIT_METHODS.contains(call.getNameAsString())
                && call.getScope()
                .filter(scope -> scope.isMethodCallExpr()
                        && GET_RUNTIME.equals(scope.asMethodCallExpr().getNameAsString()))
                .isPresent();
    }

    // В точке входа завершать процесс с нужным кодом - нормально
    private boolean isInsideMain(MethodCallExpr call) {
        return Nodes.enclosingCallable(call)
                .filter(callable -> callable instanceof MethodDeclaration method
                        && method.isStatic()
                        && MAIN.equals(method.getNameAsString()))
                .isPresent();
    }
}
