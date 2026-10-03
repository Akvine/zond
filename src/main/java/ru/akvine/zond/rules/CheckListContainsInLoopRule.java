package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
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
public class CheckListContainsInLoopRule extends AbstractRule {
    private static final String CONTAINS = "contains";
    private static final Set<String> LIST_TYPES = Set.of("List", "ArrayList", "LinkedList");

    @Override
    public String code() {
        return RuleCodes.CHECK_LIST_CONTAINS_IN_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет List.contains() в цикле";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!CONTAINS.equals(call.getNameAsString()) || call.getArguments().size() != 1 || call.getScope().isEmpty()) {
                continue;
            }

            Optional<Node> iteration = Loops.enclosingIteration(call);
            Optional<Node> declaration = LocalTypes.findDeclaration(call.getScope().get());
            if (iteration.isEmpty() || declaration.isEmpty()) {
                continue;
            }

            // Список, созданный внутри цикла, обычно мал и живет одну итерацию
            boolean isOuterList = LocalTypes.declaredType(declaration.get()).filter(LIST_TYPES::contains).isPresent()
                    && Loops.isDeclaredOutside(iteration.get(), declaration.get());
            if (isOuterList) {
                violations.add(violation(sourceFile, call,
                        "'" + call + "' в цикле: поиск по списку линейный, вместе с циклом выходит O(N²);"
                                + " сложите элементы в HashSet перед циклом"));
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
        return ErrorType.PERFORMANCE;
    }
}
