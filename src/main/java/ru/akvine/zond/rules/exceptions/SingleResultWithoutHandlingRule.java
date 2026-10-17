package ru.akvine.zond.rules.exceptions;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.TryStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class SingleResultWithoutHandlingRule extends AbstractRule {
    private static final String GET_SINGLE_RESULT = "getSingleResult";
    private static final String QUERY_FOR_OBJECT = "queryForObject";

    // Исключения, которыми JPA и JdbcTemplate сообщают о пустом результате, и их предки
    private static final Set<String> HANDLING_EXCEPTIONS = Set.of(
            "NoResultException", "NonUniqueResultException", "PersistenceException", "EmptyResultDataAccessException",
            "IncorrectResultSizeDataAccessException", "DataAccessException", "RuntimeException", "Exception",
            "Throwable");

    // Запрос с агрегатом без GROUP BY всегда возвращает ровно одну строку, как и запрос значения последовательности
    // или текущего времени (select nextval('seq'), select current_timestamp) и вставка одной строки с returning
    private static final Pattern AGGREGATE = Pattern.compile(
            ".*(\\b(count|sum|max|min|avg|exists|nextval|now)\\s*\\(|current_timestamp|current_date|localtimestamp"
                    + "|\\bsysdate\\b|next\\s+value\\s+for|buildexists|\\binsert(?!.*do\\s*nothing).*\\breturning\\b).*",
            Pattern.DOTALL);
    private static final String GROUP_BY = "group by";
    // Запрос лежит в переменной или константе, названной по смыслу: COUNT_PENDING, nextValueSql, existsQuery
    private static final Pattern SINGLE_ROW_NAME =
            Pattern.compile("(?i).*(count|exists|next_?val|next_?value|sequence|sum|total).*");

    @Override
    public String code() {
        return RuleCodes.SINGLE_RESULT_WITHOUT_HANDLING_RULE_CODE;
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

    /**
     * Текст запроса стоит в той же цепочке ({@code createQuery("select count(u) ...").getSingleResult()}) либо
     * лежит в переменной или константе, которую передают первым аргументом.
     */
    private boolean isAggregate(MethodCallExpr call) {
        List<String> texts = new ArrayList<>();
        texts.add(call.toString());
        queryArgument(call).ifPresent(query -> {
            texts.add(query.toString());
            LocalTypes.findInitializer(query).ifPresent(initializer -> texts.add(initializer.toString()));
        });
        boolean byText = texts.stream()
                .map(text -> text.toLowerCase(Locale.ROOT))
                .anyMatch(text -> AGGREGATE.matcher(text).matches() && !text.contains(GROUP_BY));
        return byText || queryArgument(call)
                .filter(query -> query.isNameExpr() || query.isFieldAccessExpr())
                .filter(query -> SINGLE_ROW_NAME.matcher(MethodCalls.receiverName(query)).matches())
                .isPresent();
    }

    // queryForObject(sql, ...) - сам запрос; у getSingleResult() - аргумент createQuery(...) в цепочке
    private Optional<Expression> queryArgument(MethodCallExpr call) {
        if (QUERY_FOR_OBJECT.equals(call.getNameAsString())) {
            return Optional.of(Nodes.unwrap(call.getArgument(0)));
        }
        return call.getScope()
                .filter(Expression::isMethodCallExpr)
                .map(Expression::asMethodCallExpr)
                .filter(scope -> !scope.getArguments().isEmpty())
                .map(scope -> Nodes.unwrap(scope.getArgument(0)));
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
