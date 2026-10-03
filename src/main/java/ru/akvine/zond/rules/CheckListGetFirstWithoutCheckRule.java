package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Set;

@Component
public class CheckListGetFirstWithoutCheckRule extends AbstractRule {
    private static final String GET = "get";
    private static final String FIRST_INDEX = "0";
    private static final Set<String> LIST_TYPES = Set.of("List", "ArrayList", "LinkedList");

    // Методы, которые заведомо возвращают список: stream.toList().get(0), repository.findAll().get(0)
    private static final Set<String> LIST_SOURCES = Set.of("toList", "findAll");

    // list.isEmpty(), list.size(), CollectionUtils.isEmpty(list)
    private static final Set<String> SIZE_CHECKS = Set.of("isEmpty", "size", "isNotEmpty");

    @Override
    public String code() {
        return RuleCodes.CHECK_LIST_GET_FIRST_WITHOUT_CHECK_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет list.get(0) без проверки списка на пустоту";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> GET.equals(call.getNameAsString()) && call.getArguments().size() == 1)
                .filter(call -> call.getArgument(0).isIntegerLiteralExpr()
                        && FIRST_INDEX.equals(call.getArgument(0).asIntegerLiteralExpr().getValue()))
                .filter(call -> call.getScope().filter(scope -> isUncheckedList(scope, call)).isPresent())
                // В тестах размер списка проверяют утверждением (hasSize, assertEquals), а не условием
                .filter(call -> !TestClasses.isInside(call))
                .map(call -> violation(sourceFile, call,
                        "'" + call + "' без проверки на пустоту: на пустом списке будет IndexOutOfBoundsException;"
                                + " проверьте isEmpty() либо используйте stream().findFirst()"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private boolean isUncheckedList(Expression scope, MethodCallExpr call) {
        Expression value = Nodes.unwrap(scope);
        if (value.isMethodCallExpr()) {
            return LIST_SOURCES.contains(value.asMethodCallExpr().getNameAsString());
        }
        return LocalTypes.typeOf(value).filter(LIST_TYPES::contains).isPresent() && !isSizeChecked(value, call);
    }

    // Проверка размера должна стоять на пути к обращению: охватывать его либо обрывать выполнение выше
    private boolean isSizeChecked(Expression list, MethodCallExpr call) {
        String name = list.toString();
        return Guards.isGuarded(call, check -> isSizeCheck(check, name));
    }

    // list.isEmpty(), list.size(), CollectionUtils.isEmpty(list)
    private boolean isSizeCheck(Expression expression, String list) {
        if (!expression.isMethodCallExpr() || !SIZE_CHECKS.contains(expression.asMethodCallExpr().getNameAsString())) {
            return false;
        }
        MethodCallExpr check = expression.asMethodCallExpr();
        return check.getScope().filter(scope -> scope.toString().equals(list)).isPresent()
                || check.getArguments().stream().anyMatch(argument -> argument.toString().equals(list));
    }
}
