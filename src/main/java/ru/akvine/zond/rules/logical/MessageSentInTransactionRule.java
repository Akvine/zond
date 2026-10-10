package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractTransactionalBlockingCallRule;
import ru.akvine.zond.rules.ContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Messaging;
import ru.akvine.zond.rules.support.ProjectWords;
import ru.akvine.zond.rules.support.TransactionalAnnotations;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Сообщение, отправленное брокеру до того, как транзакция с данными закоммичена. Правилу нужны и код,
 * и настройки: отправитель, который сам участвует в транзакции, задается в том числе свойством.
 */
@Component
public class MessageSentInTransactionRule extends AbstractTransactionalBlockingCallRule implements ContextRule {
    // Отправитель, привязанный к транзакции: сообщение уходит только вместе с коммитом
    private static final Set<String> TRANSACTIONAL_SENDER = Set.of(
            "setTransactionIdPrefix", "KafkaTransactionManager", "ChainedKafkaTransactionManager",
            "executeInTransaction", "setChannelTransacted", "RabbitTransactionManager", "JmsTransactionManager",
            "setSessionTransacted");
    private static final String KAFKA_TRANSACTION_PROPERTY = "spring.kafka.producer.transaction-id-prefix";

    // Код, который выполняется уже после коммита: TransactionSynchronization.afterCommit(), свои обертки над ним
    private static final Pattern AFTER_COMMIT = Pattern.compile(
            "(?i).*after(commit|completion).*|registerSynchronization");

    @Override
    public String code() {
        return RuleCodes.MESSAGE_SENT_IN_TRANSACTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет отправку сообщений в очередь или топик внутри @Transactional-методов, до коммита";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        boolean byProperty = context.configFiles().stream()
                .anyMatch(file -> file.find(KAFKA_TRANSACTION_PROPERTY).isPresent());
        if (byProperty || ProjectWords.hasAny(context.sources(), TRANSACTIONAL_SENDER)) {
            return List.of();
        }
        return checkProject(context.sources());
    }

    // Один файл проверяется как проект из одного файла: настроек при этом нет, и отправитель считается обычным
    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return checkProject(List.of(sourceFile));
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    // Транзакция только на чтение ничего не записывает: сообщению не с чем разойтись
    @Override
    protected boolean applies(MethodDeclaration method) {
        return TransactionalAnnotations.findEffective(method)
                .filter(TransactionalAnnotations::isReadOnly)
                .isEmpty();
    }

    @Override
    protected Optional<String> describeBlockingCall(Node node) {
        if (!(node instanceof MethodCallExpr call) || runsAfterCommit(call)) {
            return Optional.empty();
        }
        return Messaging.describeSend(call);
    }

    @Override
    protected String message(String method, String call) {
        return "Сообщение отправляется ('" + call + "') внутри @Transactional-метода '" + method + "', до коммита:"
                + " получатель может прочитать его раньше, чем данные появятся в базе, а если транзакция откатится,"
                + " сообщение уже ушло; отправляйте после коммита (@TransactionalEventListener, afterCommit)"
                + " либо через таблицу исходящих сообщений";
    }

    private boolean runsAfterCommit(MethodCallExpr call) {
        Node current = call.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof MethodDeclaration method && AFTER_COMMIT.matcher(method.getNameAsString()).matches()) {
                return true;
            }
            if (current instanceof MethodCallExpr outer && AFTER_COMMIT.matcher(outer.getNameAsString()).matches()) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }
}
