package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.stmt.CatchClause;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.RuleParameter;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.Jmix;
import ru.akvine.zond.rules.support.Messaging;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.NewObjects;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Reachability;
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.StringLiterals;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class ListenerWithoutIdempotencyRule extends AbstractRule implements ProjectRule {
    private static final RuleParameter MAX_CALL_DEPTH = new RuleParameter(
            "max-call-depth", 3, "На сколько вызовов вглубь от слушателя искать запись в БД; 0 - только в нем самом");

    private static final Set<String> SAVE_METHODS = Set.of(
            "save", "saveAll", "saveAndFlush", "saveAllAndFlush", "insert", "persist");
    private static final Set<String> JDBC_METHODS = Set.of("update", "execute", "batchUpdate");
    private static final Pattern INSERT = Pattern.compile("^\\s*insert\\b.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    // insert, который сам переживает повтор
    private static final Pattern UPSERT = Pattern.compile(
            ".*\\b(on\\s+conflict|on\\s+duplicate\\s+key|merge\\s+into|insert\\s+ignore)\\b.*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);


    // Поиск перед сохранением: так проверяют, не обработано ли сообщение раньше
    private static final Pattern LOOKUP = Pattern.compile("^(exists|find|get|count|read|load)[A-Z].*");
    private static final Pattern GUARD_WORD = Pattern.compile(
            "(?i).*(idempot|dedup|duplicate|alreadyprocessed|alreadyhandled|isprocessed|upsert).*");
    private static final Pattern DUPLICATE_EXCEPTION = Pattern.compile(
            ".*(DataIntegrityViolation|DuplicateKey|ConstraintViolation).*");

    @Override
    public String code() {
        return RuleCodes.LISTENER_WITHOUT_IDEMPOTENCY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет слушателей очередей, которые создают записи в БД без защиты от повторной доставки";
    }

    @Override
    public List<RuleParameter> parameters() {
        return List.of(MAX_CALL_DEPTH);
    }

    // Что повтор сообщения действительно создаст дубликат, по коду не доказать: защита может стоять в базе
    @Override
    public Confidence confidence() {
        return Confidence.SUSPICION;
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        CallGraph graph = CallGraph.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodDeclaration listener : Messaging.listeners(sourceFile.unit())) {
                Set<MethodDeclaration> reached = Reachability.within(graph, listener, value(MAX_CALL_DEPTH));
                Optional<MethodCallExpr> insert = reached.stream()
                        .flatMap(method -> method.findAll(MethodCallExpr.class).stream())
                        .filter(this::isInsert)
                        .findFirst();
                if (insert.isPresent() && reached.stream().noneMatch(method -> isGuarded(method, insert.get()))) {
                    violations.add(violation(sourceFile, listener,
                            "Слушатель '" + listener.getNameAsString() + "' создает запись в БД ("
                                    + describe(insert.get()) + "), а защиты от повторной доставки не видно: брокер"
                                    + " может доставить одно сообщение дважды, и запись появится два раза;"
                                    + " проверяйте по ключу сообщения, что оно уже обработано, либо поставьте"
                                    + " уникальное ограничение в базе"));
                }
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private boolean isInsert(MethodCallExpr call) {
        if (call.getArguments().isEmpty() || call.getScope().isEmpty()) {
            return false;
        }
        Expression argument = Nodes.unwrap(call.getArgument(0));
        if (JDBC_METHODS.contains(call.getNameAsString())) {
            Optional<String> sql = StringLiterals.textOf(argument);
            return sql.filter(text -> INSERT.matcher(text).matches() && !UPSERT.matcher(text).matches()).isPresent();
        }
        return SAVE_METHODS.contains(call.getNameAsString())
                && (Repositories.isRepositoryCall(call) || Jmix.isDataManager(call.getScope().get()))
                && NewObjects.isNew(argument);
    }

    private boolean isGuarded(MethodDeclaration method, MethodCallExpr insert) {
        String repository = insert.getScope().map(MethodCalls::receiverName).orElse("");
        boolean looksUp = method.findAll(MethodCallExpr.class).stream()
                .filter(call -> LOOKUP.matcher(call.getNameAsString()).matches())
                .anyMatch(call -> call.getScope().filter(scope -> MethodCalls.receiverName(scope).equals(repository)).isPresent());
        boolean namesGuard = method.findAll(SimpleName.class).stream()
                .anyMatch(name -> GUARD_WORD.matcher(name.getIdentifier()).matches());
        boolean catchesDuplicate = method.findAll(CatchClause.class).stream()
                .anyMatch(clause -> DUPLICATE_EXCEPTION.matcher(clause.getParameter().getType().asString()).matches());
        boolean upserts = StringLiterals.findComplete(method).stream()
                .anyMatch(literal -> UPSERT.matcher(literal.text()).matches());
        return looksUp || namesGuard || catchesDuplicate || upserts;
    }

    private String describe(MethodCallExpr insert) {
        return insert.getScope().map(MethodCalls::receiverName).orElse("") + "." + insert.getNameAsString();
    }
}
