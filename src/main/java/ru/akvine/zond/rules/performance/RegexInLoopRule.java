package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Loops;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.RegexCalls;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.List;

@Component
public class RegexInLoopRule extends AbstractRule {
    private static final String PATTERN = "Pattern";
    private static final String COMPILE = "compile";


    @Override
    public String code() {
        return RuleCodes.REGEX_IN_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет регулярные выражения, которые компилируются на каждой итерации цикла";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(this::compilesConstantRegex)
                .filter(call -> Loops.isRepeated(call) && !TestClasses.isInside(call))
                .map(call -> violation(sourceFile, call,
                        "'" + call.getNameAsString() + "(...)' в цикле: один и тот же шаблон компилируется заново"
                                + " на каждой итерации; вынесите Pattern.compile(...) в константу и используйте"
                                + " matcher(...), а для split - метод split(...) самого шаблона"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    // Шаблон задан строкой в коде - значит, он один и тот же на всех итерациях
    private boolean compilesConstantRegex(MethodCallExpr call) {
        boolean compiles = !call.getArguments().isEmpty() && call.getArgument(0).isStringLiteralExpr()
                && MethodCalls.isCallOn(call, PATTERN, COMPILE);
        return compiles || RegexCalls.compilesConstantRegex(call);
    }
}
