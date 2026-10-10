package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.MethodDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Messaging;
import ru.akvine.zond.rules.support.ProjectWords;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

@Component
public class ListenerWithoutDeadLetterRule extends AbstractContextRule {
    // Все, чем в RabbitMQ останавливают бесконечный возврат сообщения в очередь: отказ без возврата,
    // ограничение повторов, очередь ошибок, собственный обработчик ошибок, ручное подтверждение
    private static final Set<String> RABBIT_PROTECTION = Set.of(
            "setDefaultRequeueRejected", "AmqpRejectAndDontRequeueException", "ImmediateAcknowledgeAmqpException",
            "RejectAndDontRequeueRecoverer", "RepublishMessageRecoverer", "ConditionalRejectingErrorHandler",
            "RetryInterceptorBuilder", "RetryOperationsInterceptor", "StatefulRetryOperationsInterceptor",
            "setAdviceChain", "deadLetterExchange", "deadLetterRoutingKey", "x-dead-letter-exchange",
            "basicNack", "basicReject", "RabbitListenerErrorHandler");
    // Свойства, которые делают то же без кода: значение, при котором защита включена
    private static final Map<String, String> RABBIT_PROPERTIES = Map.of(
            "spring.rabbitmq.listener.simple.default-requeue-rejected", "false",
            "spring.rabbitmq.listener.direct.default-requeue-rejected", "false",
            "spring.rabbitmq.listener.simple.retry.enabled", "true",
            "spring.rabbitmq.listener.direct.retry.enabled", "true");

    // Kafka после нескольких попыток сообщение пропускает: сохранить его можно только отдельным топиком
    // либо своим обработчиком ошибок
    private static final Set<String> KAFKA_PROTECTION = Set.of(
            "DeadLetterPublishingRecoverer", "RetryableTopic", "DltHandler", "setCommonErrorHandler",
            "DefaultErrorHandler", "SeekToCurrentErrorHandler", "CommonErrorHandler", "KafkaListenerErrorHandler",
            "RetryTopicConfiguration");

    // Spring Cloud Stream настраивает очередь ошибок свойствами привязки
    private static final Set<String> STREAM_PROPERTY_PARTS = Set.of("auto-bind-dlq", "republish-to-dlq", "enable-dlq", "dlq-name");

    private record Listener(SourceFile source, MethodDeclaration method) {
    }

    @Override
    public String code() {
        return RuleCodes.LISTENER_WITHOUT_DEAD_LETTER_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет слушателей очередей, для которых не настроены ни очередь ошибок, ни ограничение повторов";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Listener> rabbit = listeners(context, Messaging::isRabbitListener);
        List<Listener> kafka = listeners(context, Messaging::isKafkaListener);
        List<Violation> violations = new ArrayList<>();
        if (rabbit.isEmpty() && kafka.isEmpty() || hasStreamDeadLetter(context)) {
            return violations;
        }

        Set<String> words = ProjectWords.of(context.sources());
        if (!rabbit.isEmpty() && RABBIT_PROTECTION.stream().noneMatch(words::contains) && !hasRabbitProperty(context)) {
            Listener first = rabbit.get(0);
            violations.add(violation(first.source(), first.method(),
                    "Слушатель '" + first.method().getNameAsString() + "' бросает исключение наружу, а в проекте нет"
                            + " ни очереди ошибок, ни ограничения повторов: RabbitMQ по умолчанию возвращает такое"
                            + " сообщение в очередь и тут же доставляет снова - одно сообщение, которое нельзя"
                            + " обработать, зациклит слушателя и остановит очередь (слушателей без защиты: "
                            + rabbit.size() + "); задайте spring.rabbitmq.listener.simple.default-requeue-rejected=false"
                            + " вместе с очередью ошибок (x-dead-letter-exchange) либо включите повторы"
                            + " с ограничением числа попыток"));
        }
        if (!kafka.isEmpty() && KAFKA_PROTECTION.stream().noneMatch(words::contains)) {
            Listener first = kafka.get(0);
            violations.add(violation(first.source(), first.method(),
                    "Слушатель '" + first.method().getNameAsString() + "': при ошибке обработки Spring Kafka"
                            + " повторит попытку несколько раз подряд и пропустит сообщение, оставив только запись"
                            + " в логе, - данные теряются (слушателей без защиты: " + kafka.size() + "); настройте"
                            + " DeadLetterPublishingRecoverer или @RetryableTopic, чтобы такое сообщение уходило"
                            + " в отдельный топик для разбора"));
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

    // Слушатель, который сам глушит все исключения, до брокера ошибку не доводит: повторов у него не будет
    private List<Listener> listeners(ScanContext context, Predicate<MethodDeclaration> ofBroker) {
        List<Listener> listeners = new ArrayList<>();
        for (SourceFile source : context.sources()) {
            for (MethodDeclaration method : Messaging.listeners(source.unit())) {
                if (ofBroker.test(method) && !TestClasses.isInside(method) && !Messaging.swallowsEverything(method)) {
                    listeners.add(new Listener(source, method));
                }
            }
        }
        return listeners;
    }

    private boolean hasRabbitProperty(ScanContext context) {
        for (ConfigFile file : context.configFiles()) {
            for (Map.Entry<String, String> property : RABBIT_PROPERTIES.entrySet()) {
                boolean enabled = file.find(property.getKey())
                        .filter(found -> property.getValue().equalsIgnoreCase(found.value().trim()))
                        .isPresent();
                if (enabled) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasStreamDeadLetter(ScanContext context) {
        return context.configFiles().stream()
                .flatMap(file -> file.properties().stream())
                .anyMatch(property -> STREAM_PROPERTY_PARTS.stream().anyMatch(property.key()::contains));
    }
}
