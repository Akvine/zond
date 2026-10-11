package ru.akvine.zond.rules.exceptions;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Messaging;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Сбой, превращенный в обычное значение: catch возвращает null, пустой Optional или пустую коллекцию.
 * Вызывающий код не отличит "ничего нет" от "не получилось" и продолжит работу - до NullPointerException
 * в другом месте либо до тихо потерянных данных.
 */
@Component
public class CatchReturnsDefaultRule extends AbstractRule {
    // catch (Exception) без проброса показывает отдельное правило
    private static final Set<String> BROAD = Set.of("Exception", "Throwable", "RuntimeException");
    // "Не нашлось" - это и есть ответ: пустое значение здесь переводит исключение правильно
    private static final Pattern NOTHING_FOUND = Pattern.compile(
            "(?i).*(notfound|noresult|emptyresult|nosuch|missing|notexist|absent).*");
    // "Попробовать разобрать": пустое значение на неразборчивой строке - привычный прием
    private static final Set<String> PARSE_FAILURES = Set.of(
            "NumberFormatException", "DateTimeParseException", "DateTimeException", "ParseException",
            "IllegalArgumentException", "MalformedURLException", "URISyntaxException");
    // Прерывание потока проглатывать нельзя по другой причине, и о нем говорит другое правило
    private static final String INTERRUPTED = "InterruptedException";

    private static final String NULLABLE = "Nullable";
    private static final String OPTIONAL = "Optional";
    private static final String EMPTY = "empty";
    private static final Set<String> EMPTY_FACTORIES = Set.of(
            "emptyList", "emptySet", "emptyMap", "emptySortedSet", "emptySortedMap");
    private static final Set<String> IMMUTABLE_FACTORIES = Set.of("List", "Set", "Map");
    private static final String OF = "of";
    private static final Pattern COLLECTION_TYPE = Pattern.compile(
            "^(Array|Linked|Hash|Tree|LinkedHash|ConcurrentHash|CopyOnWriteArray)?(List|Set|Map|Deque|Queue)$");

    @Override
    public String code() {
        return RuleCodes.CATCH_RETURNS_DEFAULT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет catch, который вместо ошибки возвращает null, пустой Optional или пустую коллекцию";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (CatchClause clause : sourceFile.unit().findAll(CatchClause.class)) {
            if (isExpected(clause) || Messaging.rethrows(clause)) {
                continue;
            }
            defaultOf(clause).ifPresent(value -> violations.add(violation(sourceFile, clause,
                    "catch (" + clause.getParameter().getType().asString() + ") возвращает '" + value + "': сбой"
                            + " выглядит для вызывающего кода как обычный ответ \"ничего нет\" - он продолжит работу"
                            + " и упадет позже в другом месте либо молча потеряет данные; пробросьте исключение"
                            + " (свое, с причиной) либо верните результат, в котором ошибка видна")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.EXCEPTION;
    }

    private boolean isExpected(CatchClause clause) {
        Type caught = clause.getParameter().getType();
        List<String> types = caught.isUnionType()
                ? caught.asUnionType().getElements().stream().map(LocalTypes::typeName).toList()
                : List.of(LocalTypes.typeName(caught));
        return types.stream().anyMatch(type -> BROAD.contains(type) || PARSE_FAILURES.contains(type)
                || INTERRUPTED.equals(type) || NOTHING_FOUND.matcher(type).matches());
    }

    /**
     * @return значение по умолчанию, которое catch возвращает вместо ошибки: null, Optional.empty(), пустая коллекция
     */
    private Optional<String> defaultOf(CatchClause clause) {
        // Метод с @Nullable сам объявил, что null - возможный ответ
        boolean nullable = clause.findAncestor(MethodDeclaration.class)
                .filter(method -> Annotations.has(method, NULLABLE))
                .isPresent();
        return clause.getBody().findAll(ReturnStmt.class).stream()
                .filter(statement -> !Nodes.isInNestedScope(statement, clause))
                .map(ReturnStmt::getExpression)
                .flatMap(Optional::stream)
                .map(Nodes::unwrap)
                .filter(value -> value.isNullLiteralExpr() ? !nullable : isEmptyValue(value))
                .map(Expression::toString)
                .findFirst();
    }

    // Optional.empty(), Collections.emptyList(), List.of(), new ArrayList<>()
    private boolean isEmptyValue(Expression value) {
        if (value.isObjectCreationExpr()) {
            return value.asObjectCreationExpr().getArguments().isEmpty()
                    && COLLECTION_TYPE.matcher(value.asObjectCreationExpr().getType().getNameAsString()).matches();
        }
        if (!value.isMethodCallExpr()) {
            return false;
        }
        MethodCallExpr call = value.asMethodCallExpr();
        if (!call.getArguments().isEmpty() || call.getScope().isEmpty()) {
            return false;
        }
        String owner = call.getScope().get().toString();
        String method = call.getNameAsString();
        return OPTIONAL.equals(owner) && EMPTY.equals(method)
                || EMPTY_FACTORIES.contains(method)
                || IMMUTABLE_FACTORIES.contains(owner) && OF.equals(method);
    }
}
