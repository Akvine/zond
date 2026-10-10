package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class ManualAckMisuseRule extends AbstractRule {
    private static final Set<String> CONFIRM_METHODS = Set.of("acknowledge", "basicAck");
    private static final Set<String> REJECT_METHODS = Set.of("nack", "basicNack", "basicReject");
    private static final String KAFKA_ACKNOWLEDGMENT = "Acknowledgment";
    private static final String RABBIT_LISTENER = "RabbitListener";
    private static final String MANUAL = "MANUAL";

    @Override
    public String code() {
        return RuleCodes.MANUAL_ACK_MISUSE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет ошибки ручного подтверждения сообщений: до обработки, не на всех ветках, никогда";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration listener : Messaging.listeners(sourceFile.unit())) {
            Messaging.acknowledgment(listener)
                    .filter(parameter -> listener.getBody().isPresent())
                    .ifPresent(parameter -> check(sourceFile, listener, parameter, violations));
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

    private void check(SourceFile sourceFile, MethodDeclaration listener, Parameter parameter, List<Violation> violations) {
        String name = parameter.getNameAsString();
        BlockStmt body = listener.getBody().get();
        List<MethodCallExpr> confirms = callsOn(body, name, CONFIRM_METHODS);
        List<MethodCallExpr> answers = new ArrayList<>(confirms);
        answers.addAll(callsOn(body, name, REJECT_METHODS));

        if (answers.isEmpty()) {
            // Объект отдан другому методу - подтвердить может он. Channel без ручного режима нужен не для этого
            if (!isPassedOn(body, name) && confirmsManually(listener, parameter)) {
                violations.add(violation(sourceFile, parameter,
                        "Слушатель '" + listener.getNameAsString() + "' получает '" + name + "', но ни разу не"
                                + " подтверждает сообщение: оно останется необработанным и придет снова после"
                                + " перезапуска или перебалансировки; подтверждайте сообщение, когда работа сделана"));
            }
            return;
        }

        Statement first = body.getStatements().isEmpty() ? null : body.getStatement(0);
        boolean confirmedFirst = first != null && body.getStatements().size() > 1 && first.isExpressionStmt()
                && confirms.stream().anyMatch(confirm -> first.asExpressionStmt().getExpression() == confirm);
        if (confirmedFirst) {
            violations.add(violation(sourceFile, first,
                    "Сообщение подтверждается до обработки: если дальше случится ошибка или приложение остановится,"
                            + " оно уже не вернется в очередь и будет потеряно; подтверждайте после того,"
                            + " как работа сделана"));
        }

        for (Statement statement : body.getStatements()) {
            if (statement.isTryStmt()) {
                checkBranches(sourceFile, statement.asTryStmt(), name, confirms, answers, violations);
            }
        }
    }

    // Подтверждение стоит только в try: если ни catch, ни finally, ни код после try на сообщение не отвечают,
    // при ошибке оно остается без ответа
    private void checkBranches(
            SourceFile sourceFile, TryStmt statement, String name, List<MethodCallExpr> confirms,
            List<MethodCallExpr> answers, List<Violation> violations) {
        boolean confirmedInTry = confirms.stream().anyMatch(statement.getTryBlock()::isAncestorOf);
        boolean answeredAlways = statement.getFinallyBlock().filter(block -> containsAny(block, answers)).isPresent()
                || answers.stream().anyMatch(answer -> !statement.isAncestorOf(answer) && isAfter(answer, statement));
        if (!confirmedInTry || answeredAlways) {
            return;
        }
        for (CatchClause clause : statement.getCatchClauses()) {
            boolean answered = containsAny(clause, answers) || Messaging.rethrows(clause) || isPassedOn(clause, name);
            if (!answered) {
                violations.add(violation(sourceFile, clause,
                        "При " + clause.getParameter().getType().asString() + " сообщение остается без ответа:"
                                + " в catch нет ни подтверждения, ни отказа, ни проброса исключения - оно зависнет"
                                + " до перезапуска потребителя и будет занимать место среди неподтвержденных;"
                                + " подтвердите его, отклоните (nack / basicNack) либо пробросьте исключение"));
            }
        }
    }

    // Acknowledgment приходит только в ручном режиме; Channel в слушателе RabbitMQ бывает и для других целей,
    // поэтому ручной режим должен быть назван прямо
    private boolean confirmsManually(MethodDeclaration listener, Parameter parameter) {
        if (KAFKA_ACKNOWLEDGMENT.equals(LocalTypes.typeName(parameter.getType()))) {
            return true;
        }
        return Annotations.find(listener, RABBIT_LISTENER)
                .filter(annotation -> annotation.toString().contains(MANUAL))
                .isPresent();
    }

    private List<MethodCallExpr> callsOn(Node root, String name, Set<String> methods) {
        return root.findAll(MethodCallExpr.class).stream()
                .filter(call -> methods.contains(call.getNameAsString()))
                .filter(call -> call.getScope().filter(scope -> scope.toString().equals(name)).isPresent())
                .toList();
    }

    private boolean isPassedOn(Node root, String name) {
        return root.findAll(MethodCallExpr.class).stream()
                .flatMap(call -> call.getArguments().stream())
                .anyMatch(argument -> argument.isNameExpr() && argument.asNameExpr().getNameAsString().equals(name));
    }

    private boolean containsAny(Node root, List<MethodCallExpr> calls) {
        return calls.stream().anyMatch(root::isAncestorOf);
    }

    private boolean isAfter(Node node, Node other) {
        return node.getBegin().isPresent() && other.getEnd().isPresent()
                && node.getBegin().get().isAfter(other.getEnd().get());
    }
}
