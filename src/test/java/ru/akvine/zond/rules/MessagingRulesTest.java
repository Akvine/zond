package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.rules.exceptions.KafkaSendResultIgnoredRule;
import ru.akvine.zond.rules.exceptions.ListenerSwallowsExceptionRule;
import ru.akvine.zond.rules.logical.ListenerWithoutDeadLetterRule;
import ru.akvine.zond.rules.logical.ListenerWithoutIdempotencyRule;
import ru.akvine.zond.rules.logical.ManualAckMisuseRule;
import ru.akvine.zond.rules.logical.MessageSentInTransactionRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:363 - jr:368: потеря, повтор и порядок сообщений при работе с RabbitMQ и Kafka
 */
class MessagingRulesTest {
    private static final String RABBIT_LISTENER = """
            class OrderListener {
                @RabbitListener(queues = "orders")
                void onOrder(String message) {
                    handle(message);
                }
                @RabbitListener(queues = "audit")
                void onAudit(String message) {
                    try {
                        handle(message);
                    } catch (Exception e) {
                        log.error("failed", e);
                    }
                }
            }
            """;
    private static final String KAFKA_LISTENER = """
            class PaymentListener {
                @KafkaListener(topics = "payments")
                void onPayment(String message) {
                    handle(message);
                }
            }
            """;

    @TempDir
    Path dir;

    @Test
    void listenerMustNotSwallowEverything() {
        List<Integer> lines = RuleTests.lines(new ListenerSwallowsExceptionRule(), """
                class Listeners {
                    @RabbitListener(queues = "orders")
                    void swallow(String message) {
                        try {
                            handle(message);
                        } catch (Exception e) {
                            log.error("failed", e);
                        }
                    }
                    @KafkaListener(topics = "orders")
                    void rethrow(String message) {
                        try {
                            handle(message);
                        } catch (Exception e) {
                            log.error("failed", e);
                            throw new IllegalStateException(e);
                        }
                    }
                    @KafkaListener(topics = "orders")
                    void store(String message) {
                        try {
                            handle(message);
                        } catch (Exception e) {
                            failedMessages.save(new Failed(message));
                        }
                    }
                    @KafkaListener(topics = "orders")
                    void specific(String message) {
                        try {
                            handle(message);
                        } catch (JsonProcessingException e) {
                            log.warn("bad json", e);
                        }
                    }
                    @KafkaListener(topics = "orders")
                    void manual(String message, Acknowledgment ack) {
                        try {
                            handle(message);
                        } catch (Exception e) {
                            log.error("failed", e);
                        }
                    }
                    void notListener(String message) {
                        try {
                            handle(message);
                        } catch (Exception e) {
                            log.error("failed", e);
                        }
                    }
                    @RabbitListener(queues = "batch")
                    void perItem(List<String> messages) {
                        for (String message : messages) {
                            try {
                                handle(message);
                            } catch (Exception e) {
                                log.error("failed", e);
                            }
                        }
                    }
                }
                """);

        // Сохранение сбойного сообщения - обработка; конкретное исключение ловят намеренно; ручное
        // подтверждение и разбор пачки по одному - другие случаи
        assertThat(lines).containsExactly(6);
    }

    @Test
    void rabbitListenerNeedsDeadLetterOrRetryLimit() {
        ProjectFixture unprotected = new ProjectFixture(dir.resolve("plain")).source("OrderListener.java", RABBIT_LISTENER);
        // Слушатель, который сам глушит исключения, до брокера ошибку не доводит - в счет не идет
        assertThat(unprotected.lines(new ListenerWithoutDeadLetterRule())).containsExactly("OrderListener.java:2");
        assertThat(unprotected.check(new ListenerWithoutDeadLetterRule()).get(0).message())
                .contains("слушателей без защиты: 1").contains("default-requeue-rejected");

        ProjectFixture byProperty = new ProjectFixture(dir.resolve("property"))
                .source("OrderListener.java", RABBIT_LISTENER)
                .write("src/main/resources/application.properties",
                        "spring.rabbitmq.listener.simple.default-requeue-rejected=false\n");
        assertThat(byProperty.lines(new ListenerWithoutDeadLetterRule())).isEmpty();

        ProjectFixture byRetry = new ProjectFixture(dir.resolve("retry"))
                .source("OrderListener.java", RABBIT_LISTENER)
                .write("src/main/resources/application.yml", """
                        spring:
                          rabbitmq:
                            listener:
                              simple:
                                retry:
                                  enabled: true
                        """);
        assertThat(byRetry.lines(new ListenerWithoutDeadLetterRule())).isEmpty();

        ProjectFixture byQueue = new ProjectFixture(dir.resolve("queue"))
                .source("OrderListener.java", RABBIT_LISTENER)
                .source("Queues.java", """
                        @Configuration
                        class Queues {
                            @Bean
                            Queue orders() {
                                return QueueBuilder.durable("orders").withArgument("x-dead-letter-exchange", "errors").build();
                            }
                        }
                        """);
        assertThat(byQueue.lines(new ListenerWithoutDeadLetterRule())).isEmpty();
    }

    @Test
    void kafkaListenerNeedsDeadLetterTopic() {
        ProjectFixture unprotected = new ProjectFixture(dir.resolve("plain")).source("PaymentListener.java", KAFKA_LISTENER);
        assertThat(unprotected.lines(new ListenerWithoutDeadLetterRule())).containsExactly("PaymentListener.java:2");
        assertThat(unprotected.check(new ListenerWithoutDeadLetterRule()).get(0).message()).contains("DeadLetterPublishingRecoverer");

        ProjectFixture protectedByHandler = new ProjectFixture(dir.resolve("handler"))
                .source("PaymentListener.java", KAFKA_LISTENER)
                .source("KafkaConfig.java", """
                        @Configuration
                        class KafkaConfig {
                            @Bean
                            DefaultErrorHandler errorHandler(KafkaTemplate<String, String> template) {
                                return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template));
                            }
                        }
                        """);
        assertThat(protectedByHandler.lines(new ListenerWithoutDeadLetterRule())).isEmpty();

        // Без слушателей правилу сказать нечего
        assertThat(new ProjectFixture(dir.resolve("empty")).source("Plain.java", "class Plain {}")
                .lines(new ListenerWithoutDeadLetterRule())).isEmpty();
    }

    @Test
    void messageMustNotLeaveBeforeCommit() {
        String service = """
                class OrderService {
                    @Transactional
                    public void create(Order order) {
                        repository.save(order);
                        rabbitTemplate.convertAndSend("orders", order);
                    }
                    @Transactional(readOnly = true)
                    public void report() {
                        kafkaTemplate.send("reports", "x");
                    }
                    public void plain(Order order) {
                        kafkaTemplate.send("orders", order);
                    }
                    @Transactional
                    public void deferred(Order order) {
                        repository.save(order);
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                kafkaTemplate.send("orders", order);
                            }
                        });
                    }
                    @Transactional
                    public void indirect(Order order) {
                        repository.save(order);
                        notifyOthers(order);
                    }
                    private void notifyOthers(Order order) {
                        kafkaTemplate.send("orders", order);
                    }
                    @Transactional
                    public void mail(Order order) {
                        repository.save(order);
                        mailSender.send(order.getEmail());
                    }
                }
                """;

        // Транзакция только на чтение, отправка после коммита и отправка письма - не про это правило
        assertThat(RuleTests.lines(new MessageSentInTransactionRule(), service)).containsExactly(5, 27);

        // Отправитель, привязанный к транзакции, сам ждет коммита
        ProjectFixture transactional = new ProjectFixture(dir)
                .source("OrderService.java", service)
                .write("src/main/resources/application.properties", "spring.kafka.producer.transaction-id-prefix=tx-\n");
        assertThat(transactional.lines((ContextRule) new MessageSentInTransactionRule())).isEmpty();
    }

    @Test
    void kafkaSendResultMustBeChecked() {
        String sender = """
                class Sender {
                    void fire(String message) {
                        kafkaTemplate.send("topic", message);
                    }
                    void handled(String message) {
                        kafkaTemplate.send("topic", message).whenComplete((result, error) -> log(error));
                    }
                    void awaited(String message) throws Exception {
                        kafkaTemplate.send("topic", message).get(5, TimeUnit.SECONDS);
                    }
                    CompletableFuture<?> returned(String message) {
                        return kafkaTemplate.send("topic", message);
                    }
                    void rabbit(String message) {
                        rabbitTemplate.convertAndSend("queue", message);
                    }
                    void other(String message) {
                        mailSender.send(message);
                    }
                    void inTransaction(String message) {
                        kafkaTemplate.executeInTransaction(operations -> {
                            operations.send("topic", message);
                            return true;
                        });
                    }
                }
                """;
        assertThat(RuleTests.lines(new KafkaSendResultIgnoredRule(), sender)).containsExactly(3);

        // Свой слушатель отправки получает каждую ошибку
        ProjectFixture withListener = new ProjectFixture(dir)
                .source("Sender.java", sender)
                .source("SendErrors.java", "class SendErrors implements ProducerListener<String, String> {}");
        assertThat(withListener.lines(new KafkaSendResultIgnoredRule())).isEmpty();
    }

    @Test
    void manualAcknowledgmentMustCoverEveryOutcome() {
        List<Integer> lines = RuleTests.lines(new ManualAckMisuseRule(), """
                class Listeners {
                    @KafkaListener(topics = "a")
                    void never(String message, Acknowledgment ack) {
                        handle(message);
                    }
                    @KafkaListener(topics = "b")
                    void early(String message, Acknowledgment ack) {
                        ack.acknowledge();
                        handle(message);
                    }
                    @KafkaListener(topics = "c")
                    void onlyOnSuccess(String message, Acknowledgment ack) {
                        try {
                            handle(message);
                            ack.acknowledge();
                        } catch (Exception e) {
                            log.error("failed", e);
                        }
                    }
                    @KafkaListener(topics = "d")
                    void correct(String message, Acknowledgment ack) {
                        try {
                            handle(message);
                            ack.acknowledge();
                        } catch (Exception e) {
                            ack.nack(Duration.ofSeconds(1));
                        }
                    }
                    @KafkaListener(topics = "e")
                    void inFinally(String message, Acknowledgment ack) {
                        try {
                            handle(message);
                        } catch (Exception e) {
                            log.error("failed", e);
                        } finally {
                            ack.acknowledge();
                        }
                    }
                    @KafkaListener(topics = "f")
                    void delegated(String message, Acknowledgment ack) {
                        processor.process(message, ack);
                    }
                    @RabbitListener(queues = "g")
                    void channelForOtherNeeds(String message, Channel channel) {
                        handle(message);
                    }
                    @RabbitListener(queues = "h", ackMode = "MANUAL")
                    void rabbitNever(String message, Channel channel) {
                        handle(message);
                    }
                    @RabbitListener(queues = "i", ackMode = "MANUAL")
                    void rabbitRethrows(Message message, Channel channel) throws IOException {
                        try {
                            handle(message);
                            channel.basicAck(tag(message), false);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                }
                """);

        // 3 и 48 - сообщение не подтверждается никогда, 8 - подтверждено до обработки, 16 - при ошибке без ответа
        assertThat(lines).containsExactly(3, 8, 16, 48);
    }

    @Test
    void listenerThatInsertsNeedsDuplicateProtection() {
        ProjectFixture project = new ProjectFixture(dir)
                .source("PaymentListener.java", """
                        class PaymentListener {
                            private PaymentService paymentService;
                            private RefundService refundService;
                            private PaymentRepository paymentRepository;
                            @KafkaListener(topics = "payments")
                            void onPayment(PaymentEvent event) {
                                paymentService.register(event);
                            }
                            @KafkaListener(topics = "refunds")
                            void onRefund(RefundEvent event) {
                                refundService.register(event);
                            }
                            @KafkaListener(topics = "statuses")
                            void onStatus(StatusEvent event) {
                                Payment payment = paymentRepository.findById(event.getId()).orElseThrow();
                                payment.setStatus(event.getStatus());
                                paymentRepository.save(payment);
                            }
                            @RabbitListener(queues = "raw")
                            void onRaw(String body) {
                                jdbcTemplate.update("insert into raw_messages (body) values (?)", body);
                            }
                            @RabbitListener(queues = "safe")
                            void onSafe(String body) {
                                jdbcTemplate.update("insert into raw_messages (body) values (?) on conflict do nothing", body);
                            }
                        }
                        """)
                .source("PaymentService.java", """
                        class PaymentService {
                            private PaymentRepository paymentRepository;
                            void register(PaymentEvent event) {
                                paymentRepository.save(new Payment(event.getAmount()));
                            }
                        }
                        """)
                .source("RefundService.java", """
                        class RefundService {
                            private RefundRepository refundRepository;
                            void register(RefundEvent event) {
                                if (refundRepository.existsByEventId(event.getId())) {
                                    return;
                                }
                                refundRepository.save(new Refund(event.getId()));
                            }
                        }
                        """);

        ListenerWithoutIdempotencyRule rule = new ListenerWithoutIdempotencyRule();
        // Проверка перед вставкой, изменение найденной записи и insert ... on conflict повтор переживают
        assertThat(project.lines(rule)).containsExactly("PaymentListener.java:19", "PaymentListener.java:5");
        // Защита может стоять в базе: по коду это только подозрение
        assertThat(rule.confidence()).isEqualTo(Confidence.SUSPICION);
    }
}
