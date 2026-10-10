package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.type.Type;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Обмен сообщениями через брокер: кто слушает очередь или топик и кто в них отправляет.
 * Общие признаки для правил о потере, повторе и порядке сообщений.
 */
@UtilityClass
public class Messaging {
    private static final Set<String> RABBIT_LISTENERS = Set.of("RabbitListener", "RabbitHandler");
    private static final Set<String> KAFKA_LISTENERS = Set.of("KafkaListener", "KafkaHandler");
    private static final Set<String> OTHER_LISTENERS = Set.of("JmsListener", "SqsListener", "StreamListener");

    // Объект, через который подтверждают сообщение вручную
    private static final Set<String> ACKNOWLEDGMENT_TYPES = Set.of("Acknowledgment", "Channel");

    private static final Set<String> RABBIT_TEMPLATES = Set.of(
            "RabbitTemplate", "AmqpTemplate", "RabbitOperations", "RabbitMessagingTemplate", "AsyncRabbitTemplate");
    private static final Set<String> KAFKA_TEMPLATES = Set.of(
            "KafkaTemplate", "KafkaOperations", "ReplyingKafkaTemplate", "KafkaProducer");
    private static final Set<String> OTHER_TEMPLATES = Set.of(
            "JmsTemplate", "JmsMessagingTemplate", "StreamBridge", "SqsTemplate", "QueueMessagingTemplate");

    // Если тип определить не удалось, отправителя выдает имя: orderKafkaTemplate, rabbitTemplate
    private static final Pattern KAFKA_NAME = Pattern.compile(".*kafka(template|producer|operations).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEMPLATE_NAME = Pattern.compile(
            ".*(rabbittemplate|amqptemplate|kafkatemplate|kafkaproducer|jmstemplate|streambridge|sqstemplate).*",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> SEND_METHODS = Set.of(
            "send", "sendDefault", "convertAndSend", "sendAndReceive", "convertSendAndReceive",
            "convertSendAndReceiveAsType");
    private static final Set<String> KAFKA_SEND_METHODS = Set.of("send", "sendDefault");

    private static final Set<String> BROAD_EXCEPTIONS = Set.of("Exception", "Throwable", "RuntimeException");

    /**
     * @return методы, которые получают сообщения из очереди или топика
     */
    public List<MethodDeclaration> listeners(CompilationUnit unit) {
        return unit.findAll(MethodDeclaration.class).stream().filter(Messaging::isListener).toList();
    }

    public boolean isListener(MethodDeclaration method) {
        return isRabbitListener(method) || isKafkaListener(method) || Annotations.hasAny(method, OTHER_LISTENERS);
    }

    public boolean isRabbitListener(MethodDeclaration method) {
        return Annotations.hasAny(method, RABBIT_LISTENERS);
    }

    public boolean isKafkaListener(MethodDeclaration method) {
        return Annotations.hasAny(method, KAFKA_LISTENERS);
    }

    /**
     * @return параметр, через который слушатель подтверждает сообщение сам: Acknowledgment у Kafka, Channel у RabbitMQ
     */
    public Optional<Parameter> acknowledgment(MethodDeclaration listener) {
        return listener.getParameters().stream()
                .filter(parameter -> ACKNOWLEDGMENT_TYPES.contains(typeName(parameter.getType())))
                .findFirst();
    }

    /**
     * @return описание отправки (kafkaTemplate.send), если вызов отдает сообщение брокеру
     */
    public Optional<String> describeSend(MethodCallExpr call) {
        if (call.getScope().isEmpty() || !SEND_METHODS.contains(call.getNameAsString())) {
            return Optional.empty();
        }
        Expression scope = call.getScope().get();
        String receiver = MethodCalls.receiverName(scope);
        boolean isTemplate = isAnyOf(scope, RABBIT_TEMPLATES, receiver)
                || isAnyOf(scope, KAFKA_TEMPLATES, receiver)
                || isAnyOf(scope, OTHER_TEMPLATES, receiver);
        return isTemplate ? Optional.of(receiver + "." + call.getNameAsString()) : Optional.empty();
    }

    /**
     * @return true для kafkaTemplate.send(...): отправка в Kafka не ждет ответа брокера
     */
    public boolean isKafkaSend(MethodCallExpr call) {
        if (call.getScope().isEmpty() || !KAFKA_SEND_METHODS.contains(call.getNameAsString())) {
            return false;
        }
        Expression scope = call.getScope().get();
        return LocalTypes.isAnyOf(scope, KAFKA_TEMPLATES,
                () -> KAFKA_NAME.matcher(MethodCalls.receiverName(scope)).matches());
    }

    /**
     * @return true, если catch ловит все подряд: Exception, RuntimeException или Throwable
     */
    public boolean catchesEverything(CatchClause clause) {
        Type type = clause.getParameter().getType();
        if (type.isUnionType()) {
            return type.asUnionType().getElements().stream().anyMatch(element -> BROAD_EXCEPTIONS.contains(typeName(element)));
        }
        return BROAD_EXCEPTIONS.contains(typeName(type));
    }

    /**
     * @return true, если из catch исключение уходит дальше
     */
    public boolean rethrows(CatchClause clause) {
        return !clause.getBody().findAll(ThrowStmt.class).isEmpty();
    }

    /**
     * @return true, если слушатель сам глушит любое исключение: весь его код стоит в try с catch (Exception),
     * из которого ничего не бросается. До брокера ошибка у такого слушателя не доходит
     */
    public boolean swallowsEverything(MethodDeclaration listener) {
        return listener.getBody().stream()
                .flatMap(body -> body.getStatements().stream())
                .filter(statement -> statement.isTryStmt())
                .flatMap(statement -> statement.asTryStmt().getCatchClauses().stream())
                .anyMatch(clause -> catchesEverything(clause) && !rethrows(clause));
    }

    /**
     * @return класс, в котором объявлен метод
     */
    public Optional<ClassOrInterfaceDeclaration> ownerOf(Node node) {
        return node.findAncestor(ClassOrInterfaceDeclaration.class);
    }

    private boolean isAnyOf(Expression scope, Set<String> types, String receiver) {
        return LocalTypes.isAnyOf(scope, types, () -> TEMPLATE_NAME.matcher(receiver).matches());
    }

    private String typeName(Type type) {
        return LocalTypes.typeName(type);
    }
}
