package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckIteratorNextWithoutHasNextRule extends AbstractRule {
    private static final String NEXT = "next";
    private static final String ITERATOR = "iterator";
    private static final Set<String> ITERATOR_TYPES = Set.of("Iterator", "ListIterator");
    private static final Set<String> HAS_NEXT_CHECKS = Set.of("hasNext");
    private static final Set<String> SIZE_CHECKS = Set.of("isEmpty", "size", "isNotEmpty");

    @Override
    public String code() {
        return RuleCodes.CHECK_ITERATOR_NEXT_WITHOUT_HAS_NEXT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет Iterator.next() без проверки hasNext()";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> NEXT.equals(call.getNameAsString()) && call.getArguments().isEmpty())
                .filter(call -> call.getScope().filter(scope -> isUnchecked(scope, call)).isPresent())
                .map(call -> violation(sourceFile, call,
                        "'" + call + "' без проверки hasNext(): если элементов больше нет, будет"
                                + " NoSuchElementException; проверяйте hasNext() перед next()"))
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

    private boolean isUnchecked(Expression scope, MethodCallExpr call) {
        Expression value = Nodes.unwrap(scope);

        // items.iterator().next(): защитой считаем проверку размера самой коллекции
        if (value.isMethodCallExpr() && ITERATOR.equals(value.asMethodCallExpr().getNameAsString())) {
            return value.asMethodCallExpr().getScope()
                    .filter(collection -> !hasCheck(collection.toString(), call, SIZE_CHECKS))
                    .isPresent();
        }
        return LocalTypes.typeOf(value).filter(ITERATOR_TYPES::contains).isPresent()
                && !hasCheck(value.toString(), call, HAS_NEXT_CHECKS);
    }

    private boolean hasCheck(String target, MethodCallExpr call, Set<String> checks) {
        Optional<Node> callable = Nodes.enclosingCallable(call);
        return callable.isPresent() && callable.get().findAll(MethodCallExpr.class).stream()
                .filter(check -> checks.contains(check.getNameAsString()))
                .anyMatch(check -> check.getScope().filter(scope -> scope.toString().equals(target)).isPresent()
                        || check.getArguments().stream().anyMatch(argument -> argument.toString().equals(target)));
    }
}
