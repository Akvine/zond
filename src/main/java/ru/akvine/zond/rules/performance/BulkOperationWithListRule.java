package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code removeAll}, {@code retainAll} и {@code containsAll}, внутри которых спрятан перебор списка.
 * <p>
 * {@code a.removeAll(b)} для каждого элемента {@code a} вызывает {@code b.contains(...)}. Если {@code b} -
 * список, это перебор, и вся операция стоит O(N×M): два списка по сто тысяч элементов - десять миллиардов
 * сравнений. С множеством на месте списка та же строка работает за O(N). У {@code containsAll} перебирается
 * сам получатель: {@code list.containsAll(other)} ищет в списке каждый элемент {@code other}.
 */
@Component
public class BulkOperationWithListRule extends AbstractRule {
    private static final Set<String> SEARCH_IN_ARGUMENT = Set.of("removeAll", "retainAll");
    private static final String CONTAINS_ALL = "containsAll";
    private static final Set<String> LIST_TYPES =
            Set.of("List", "ArrayList", "LinkedList", "CopyOnWriteArrayList", "Vector");

    @Override
    public String code() {
        return RuleCodes.BULK_OPERATION_WITH_LIST_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет removeAll(), retainAll() и containsAll(), в которых поиск идет перебором списка";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (call.getArguments().size() != 1 || call.getScope().isEmpty() || TestClasses.isInside(call)) {
                continue;
            }
            message(call).ifPresent(text -> violations.add(violation(sourceFile, call.getName(), text)));
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

    private Optional<String> message(MethodCallExpr call) {
        Expression receiver = Nodes.unwrap(call.getScope().orElseThrow());
        Expression argument = Nodes.unwrap(call.getArgument(0));
        String name = call.getNameAsString();
        if (SEARCH_IN_ARGUMENT.contains(name) && isList(argument)) {
            return Optional.of("'" + Nodes.text(call) + "' для каждого элемента '" + receiver + "' ищет его в списке '"
                    + argument + "' перебором: вся операция стоит O(N×M); передайте множество - "
                    + receiver + "." + name + "(new HashSet<>(" + argument + "))");
        }
        if (CONTAINS_ALL.equals(name) && isList(receiver)) {
            return Optional.of("'" + Nodes.text(call) + "' ищет каждый элемент '" + argument + "' в списке '" + receiver
                    + "' перебором: вся операция стоит O(N×M); сравнивайте с множеством -"
                    + " new HashSet<>(" + receiver + ").containsAll(" + argument + ")");
        }
        return Optional.empty();
    }

    // Список в переменной, параметре или поле. Список из нескольких значений, записанный прямо в вызове
    // (List.of(a, b)), мал, и о нем правило молчит
    private boolean isList(Expression expression) {
        return LocalTypes.findDeclaration(expression)
                .flatMap(LocalTypes::declaredType)
                .filter(LIST_TYPES::contains)
                .isPresent();
    }
}
