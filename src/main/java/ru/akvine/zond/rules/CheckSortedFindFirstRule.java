package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;

@Component
public class CheckSortedFindFirstRule extends AbstractRule {
    private static final String SORTED = "sorted";
    private static final String FIND_FIRST = "findFirst";

    @Override
    public String code() {
        return RuleCodes.CHECK_SORTED_FIND_FIRST_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет sorted().findFirst() вместо min() / max()";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // Именно подряд: sorted().skip(1).findFirst() или sorted().filter(...).findFirst() через min() не выразить
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> FIND_FIRST.equals(call.getNameAsString()) && call.getArguments().isEmpty())
                .filter(call -> call.getScope()
                        .map(Nodes::unwrap)
                        .filter(scope -> scope.isMethodCallExpr()
                                && SORTED.equals(scope.asMethodCallExpr().getNameAsString()))
                        .isPresent())
                .map(call -> violation(sourceFile, call,
                        "sorted(...).findFirst(): ради одного элемента сортируется весь стрим, O(N log N) вместо O(N);"
                                + " используйте min(...) или max(...)"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.STREAM;
    }
}
