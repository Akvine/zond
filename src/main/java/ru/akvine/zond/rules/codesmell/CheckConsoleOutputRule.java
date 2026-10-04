package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.MethodCalls;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class CheckConsoleOutputRule extends AbstractRule {
    private static final String PRINT_STACK_TRACE = "printStackTrace";
    private static final String SYSTEM = "System";
    private static final Set<String> CONSOLE_STREAMS = Set.of("out", "err");

    @Override
    public String code() {
        return RuleCodes.CHECK_CONSOLE_OUTPUT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет printStackTrace() и вывод через System.out / System.err вместо логгера";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (PRINT_STACK_TRACE.equals(call.getNameAsString())
                    && call.getArguments().isEmpty()
                    && call.getScope().isPresent()) {
                violations.add(violation(sourceFile, call,
                        "'" + call + "' пишет в консоль мимо логгера: запись не попадет в файлы логов"
                                + " и потеряет контекст; используйте log.error(..., exception)"));
            }

            if (call.getScope().filter(this::isConsoleStream).isPresent()) {
                violations.add(violation(sourceFile, call,
                        "Вывод через " + call.getScope().get() + " вместо логгера: нет уровня, времени"
                                + " и возможности отключить; используйте логгер"));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    // System.out, System.err
    private boolean isConsoleStream(Expression scope) {
        return scope.isFieldAccessExpr()
                && CONSOLE_STREAMS.contains(scope.asFieldAccessExpr().getNameAsString())
                && MethodCalls.isType(scope.asFieldAccessExpr().getScope(), SYSTEM);
    }
}
