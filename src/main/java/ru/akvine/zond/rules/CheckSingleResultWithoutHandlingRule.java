package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.TryStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class CheckSingleResultWithoutHandlingRule extends AbstractRule {
    private static final String GET_SINGLE_RESULT = "getSingleResult";
    private static final String QUERY_FOR_OBJECT = "queryForObject";

    // Исключения, которыми JPA и JdbcTemplate сообщают о пустом результате, и их предки
    private static final Set<String> HANDLING_EXCEPTIONS = Set.of(
            "NoResultException", "NonUniqueResultException", "PersistenceException", "EmptyResultDataAccessException",
            "IncorrectResultSizeDataAccessException", "DataAccessException", "RuntimeException", "Exception",
            "Throwable");

    // Запрос с агрегатом без GROUP BY всегда возвращает ровно одну строку
    private static final Pattern AGGREGATE = Pattern.compile(".*\\b(count|sum|max|min|avg|exists)\\s*\\(.*", Pattern.DOTALL);
    private static final String GROUP_BY = "group by";

    @Override
    public String code() {
        return RuleCodes.CHECK_SINGLE_RESULT_WITHOUT_HANDLING_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет getSingleResult() и queryForObject() без обработки пустого результата";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(this::expectsSingleRow)
                .filter(call -> !isAggregate(call) && !isHandled(call) && !TestClasses.isInside(call))
                .map(call -> violation(sourceFile, call,
                        "'" + call.getNameAsString() + "(...)' без обработки пустого результата: если строки"
                                + " не найдется, метод бросит исключение (NoResultException,"
                                + " EmptyResultDataAccessException); перехватите его либо получайте список"
                                + " и берите первый элемент"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.EXCEPTION;
    }

    private boolean expectsSingleRow(MethodCallExpr call) {
        String method = call.getNameAsString();
        return GET_SINGLE_RESULT.equals(method) && call.getArguments().isEmpty() && call.getScope().isPresent()
                || QUERY_FOR_OBJECT.equals(method) && !call.getArguments().isEmpty();
    }

    // Текст запроса стоит в той же цепочке: createQuery("select count(u) ...").getSingleResult()
    private boolean isAggregate(MethodCallExpr call) {
        String text = call.toString().toLowerCase(Locale.ROOT);
        return AGGREGATE.matcher(text).matches() && !text.contains(GROUP_BY);
    }

    private boolean isHandled(MethodCallExpr call) {
        Node child = call;
        Node current = call.getParentNode().orElse(null);
        while (current != null) {
            Node inner = child;
            if (current instanceof TryStmt tryStmt && tryStmt.getTryBlock() == inner
                    && tryStmt.getCatchClauses().stream()
                    .anyMatch(clause -> HANDLING_EXCEPTIONS.stream().anyMatch(clause.getParameter().getType().toString()::contains))) {
                return true;
            }
            child = current;
            current = current.getParentNode().orElse(null);
        }
        return false;
    }
}
