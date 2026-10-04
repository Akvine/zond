package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loops;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckStreamInLoopRule extends AbstractRule {
    private static final Set<String> STREAM_METHODS = Set.of("stream", "parallelStream");

    @Override
    public String code() {
        return RuleCodes.CHECK_STREAM_IN_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет стрим по внешней коллекции, который создается на каждой итерации цикла";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!STREAM_METHODS.contains(call.getNameAsString())
                    || !call.getArguments().isEmpty()
                    || call.getScope().isEmpty()) {
                continue;
            }

            // Коллекция объявлена до цикла, значит на каждой итерации она обходится заново целиком.
            // Стрим по коллекции, полученной внутри итерации (item.getChildren().stream()), сюда не попадает
            Optional<Node> iteration = Loops.enclosingIteration(call);
            Optional<Node> declaration = LocalTypes.findDeclaration(call.getScope().get());
            if (iteration.isPresent()
                    && declaration.isPresent()
                    && Loops.isDeclaredOutside(iteration.get(), declaration.get())) {
                violations.add(violation(sourceFile, call,
                        "Стрим по '" + call.getScope().get() + "' создается на каждой итерации: коллекция всякий раз"
                                + " обходится заново, вместе с циклом выходит O(N×M); вынесите вычисление из цикла"
                                + " или заранее сложите данные в Map / Set"));
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
        return ErrorType.PERFORMANCE;
    }
}
