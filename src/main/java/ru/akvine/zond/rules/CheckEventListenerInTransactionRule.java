package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Set;

@Component
public class CheckEventListenerInTransactionRule extends AbstractRule {
    private static final String EVENT_LISTENER = "EventListener";

    // Действия, которые нельзя отменить откатом транзакции
    private static final Set<String> IRREVERSIBLE_METHODS = Set.of(
            "send", "convertAndSend", "publish", "postForObject", "postForEntity", "exchange", "sendMessage",
            "sendMail", "sendEmail");

    @Override
    public String code() {
        return RuleCodes.CHECK_EVENT_LISTENER_IN_TRANSACTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет @EventListener, который работает с БД или отправляет сообщения наружу";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodDeclaration.class).stream()
                .filter(method -> Annotations.has(method, EVENT_LISTENER))
                .filter(this::dependsOnTransactionOutcome)
                .map(method -> violation(sourceFile, method,
                        "@EventListener '" + method.getNameAsString() + "' выполняется сразу при публикации события,"
                                + " еще внутри транзакции издателя: если она потом откатится, письмо уже отправлено,"
                                + " а чтение из БД не увидит ее изменений; используйте @TransactionalEventListener"
                                + " (по умолчанию - после фиксации)"))
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

    // Слушатель ходит в репозиторий либо делает то, что нельзя откатить
    private boolean dependsOnTransactionOutcome(MethodDeclaration method) {
        return TransactionalAnnotations.isPresent(method)
                || method.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> Repositories.isRepositoryCall(call)
                        || (call.getScope().isPresent() && IRREVERSIBLE_METHODS.contains(call.getNameAsString())));
    }
}
