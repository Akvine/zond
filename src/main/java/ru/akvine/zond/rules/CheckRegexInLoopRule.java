package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Set;

@Component
public class CheckRegexInLoopRule extends AbstractRule {
    private static final String PATTERN = "Pattern";
    private static final String COMPILE = "compile";
    private static final String MATCHES = "matches";

    // split(",") в список не входит: для разделителя из одного символа шаблон не компилируется
    private static final Set<String> STRING_REGEX_METHODS = Set.of("replaceAll", "replaceFirst");

    @Override
    public String code() {
        return RuleCodes.CHECK_REGEX_IN_LOOP_RULE_CODE;
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
                                + " matcher(...)"))
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
        if (call.getArguments().isEmpty() || !call.getArgument(0).isStringLiteralExpr()) {
            return false;
        }
        String method = call.getNameAsString();
        return MethodCalls.isCallOn(call, PATTERN, COMPILE)
                || call.getScope().isPresent() && STRING_REGEX_METHODS.contains(method)
                || call.getScope().isPresent() && MATCHES.equals(method) && call.getArguments().size() == 1;
    }
}
