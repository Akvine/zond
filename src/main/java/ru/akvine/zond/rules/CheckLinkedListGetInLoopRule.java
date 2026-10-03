package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;

@Component
public class CheckLinkedListGetInLoopRule extends AbstractRule {
    private static final String GET = "get";
    private static final String LINKED_LIST = "LinkedList";

    @Override
    public String code() {
        return RuleCodes.CHECK_LINKED_LIST_GET_IN_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет LinkedList.get(index) в цикле";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> GET.equals(call.getNameAsString()) && call.getArguments().size() == 1)
                .filter(call -> call.getScope().filter(this::isLinkedList).isPresent())
                .filter(Loops::isRepeated)
                .map(call -> violation(sourceFile, call,
                        "'" + call + "' в цикле: LinkedList добирается до элемента перебором с начала списка,"
                                + " обход по индексу получается квадратичным; используйте итератор, for-each"
                                + " или ArrayList"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    // По объявленному типу либо по инициализатору: List<X> list = new LinkedList<>()
    private boolean isLinkedList(Expression scope) {
        if (LocalTypes.typeOf(scope).filter(LINKED_LIST::equals).isPresent()) {
            return true;
        }
        return LocalTypes.findInitializer(scope)
                .filter(Expression::isObjectCreationExpr)
                .map(initializer -> initializer.asObjectCreationExpr().getType().getNameAsString())
                .filter(LINKED_LIST::equals)
                .isPresent();
    }
}
