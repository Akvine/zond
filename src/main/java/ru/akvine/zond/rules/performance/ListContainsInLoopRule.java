package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CheckThenAdd;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loops;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class ListContainsInLoopRule extends AbstractRule {
    private static final String CONTAINS = "contains";
    private static final Set<String> LIST_TYPES = Set.of("List", "ArrayList", "LinkedList");
    private static final Set<String> LITERAL_FACTORIES = Set.of("of", "asList", "listOf");
    private static final int SMALL_LIST = 16;

    @Override
    public String code() {
        return RuleCodes.LIST_CONTAINS_IN_LOOP_RULE_CODE;
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
            // О проверке на повтор перед добавлением сообщает ContainsBeforeAddRule: там совет точнее -
            // проверять результат Set.add
            if (isOuterList && CheckThenAdd.addAfter(call).isEmpty() && !isSmallLiteralList(declaration.get())) {
                violations.add(violation(sourceFile, call,
                        "'" + call + "' в цикле: поиск по списку линейный, вместе с циклом выходит O(N²);"
                                + " сложите элементы в HashSet перед циклом"));
            }
        }
        return violations;
    }

    // List.of("<", ">", "=") - несколько элементов, записанных в коде: поиск по ним не растет вместе с данными
    private boolean isSmallLiteralList(Node declaration) {
        if (!(declaration instanceof VariableDeclarator variable) || variable.getInitializer().isEmpty()) {
            return false;
        }
        Expression initializer = Nodes.unwrap(variable.getInitializer().get());
        return initializer.isMethodCallExpr()
                && LITERAL_FACTORIES.contains(initializer.asMethodCallExpr().getNameAsString())
                && initializer.asMethodCallExpr().getArguments().size() <= SMALL_LIST
                && initializer.asMethodCallExpr().getArguments().stream().allMatch(argument -> argument.isLiteralExpr()
                || argument.isFieldAccessExpr() || argument.isNameExpr());
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
