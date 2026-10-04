package ru.akvine.zond.rules;

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
public class CheckInMemoryFilteringRule extends AbstractRule {
    private static final String FIND_ALL = "findAll";
    private static final String STREAM = "stream";
    private static final Set<String> COUNTING_METHODS = Set.of("size", "isEmpty");
    private static final Set<String> SELECTING_OPERATIONS = Set.of("filter", "anyMatch", "noneMatch", "allMatch", "count");

    @Override
    public String code() {
        return RuleCodes.CHECK_IN_MEMORY_FILTERING_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет отбор и подсчет в памяти после findAll() вместо запроса к БД";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!FIND_ALL.equals(call.getNameAsString()) || !call.getArguments().isEmpty()
                    || !Repositories.isRepositoryCall(call) || TestClasses.isInside(call)) {
                continue;
            }
            describeMisuse(call).ifPresent(advice -> violations.add(violation(sourceFile, call,
                    "'" + call + "' загружает всю таблицу, а нужное выбирается уже в памяти: с ростом данных"
                            + " это станет самым медленным местом; " + advice)));
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

    private Optional<String> describeMisuse(MethodCallExpr findAll) {
        Optional<MethodCallExpr> next = nextCall(findAll);
        if (next.isEmpty()) {
            return Optional.empty();
        }
        if (COUNTING_METHODS.contains(next.get().getNameAsString())) {
            return Optional.of("используйте count() или existsBy...()");
        }
        // findAll().stream().filter(...)
        boolean selects = STREAM.equals(next.get().getNameAsString())
                && nextCall(next.get()).filter(operation -> SELECTING_OPERATIONS.contains(operation.getNameAsString()))
                .isPresent();
        return selects ? Optional.of("перенесите условие в запрос: findBy...(), @Query") : Optional.empty();
    }

    // Вызов, для которого этот - получатель: a.b().c() -> для b() это c()
    private Optional<MethodCallExpr> nextCall(MethodCallExpr call) {
        return call.getParentNode()
                .filter(parent -> parent instanceof MethodCallExpr)
                .map(parent -> (MethodCallExpr) parent)
                .filter(parent -> parent.getScope().filter(scope -> scope == call).isPresent());
    }
}
