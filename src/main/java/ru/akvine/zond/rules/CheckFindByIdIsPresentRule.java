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
public class CheckFindByIdIsPresentRule extends AbstractRule {
    private static final String FIND_BY_ID = "findById";
    private static final Set<String> PRESENCE_CHECKS = Set.of("isPresent", "isEmpty");

    @Override
    public String code() {
        return RuleCodes.CHECK_FIND_BY_ID_IS_PRESENT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет проверку существования записи через findById().isPresent()";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> PRESENCE_CHECKS.contains(call.getNameAsString()) && call.getArguments().isEmpty())
                .filter(call -> call.getScope()
                        .map(Nodes::unwrap)
                        .filter(scope -> scope.isMethodCallExpr()
                                && FIND_BY_ID.equals(scope.asMethodCallExpr().getNameAsString())
                                && Repositories.isRepositoryCall(scope.asMethodCallExpr()))
                        .isPresent())
                .map(call -> violation(sourceFile, call,
                        "'" + call + "': ради ответа да / нет из БД загружается вся сущность со всеми полями;"
                                + " используйте existsById(...)"))
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
}
