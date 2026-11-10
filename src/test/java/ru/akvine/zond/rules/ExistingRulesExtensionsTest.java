package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.concurrency.TransactionalWithAsyncRule;
import ru.akvine.zond.rules.logical.CollectionModificationInForEachRule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Дополнения к jr:103 и jr:21: @Async-метод другого бина внутри транзакции и молчаливый пропуск элемента в for-each
 */
class ExistingRulesExtensionsTest {

    @Test
    void asyncMethodOfAnotherBeanDoesNotJoinTransaction() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("Notifier.java", """
                @Service
                class Notifier {
                    @Async
                    public void notifyAsync(Order order) {
                    }
                    public void notifyNow(Order order) {
                    }
                }
                """);
        files.put("Indexer.java", """
                @Async
                @Component
                class Indexer {
                    public void reindex(Order order) {
                    }
                }
                """);
        files.put("OrderService.java", """
                @Service
                class OrderService {
                    private final Notifier notifier;
                    private final Indexer indexer;
                    private final OrderRepository repository;
                    @Transactional
                    public void place(Order order) {
                        repository.save(order);
                        notifier.notifyAsync(order);
                        indexer.reindex(order);
                        notifier.notifyNow(order);
                        this.audit(order);
                    }
                    public void resend(Order order) {
                        notifier.notifyAsync(order);
                    }
                    @Async
                    public void audit(Order order) {
                    }
                }
                """);

        List<Violation> violations = RuleTests.checkProject(new TransactionalWithAsyncRule(), files);

        // Вне транзакции вызов уместен, а вызов своего @Async-метода идет мимо прокси - о нем сообщает jr:157
        assertThat(violations).extracting(Violation::line).containsExactly(9, 10);
        assertThat(violations.get(0).message()).contains(
                "Вызов @Async-метода 'notifier.notifyAsync(...)'", "@Transactional-метода 'place'",
                "транзакция на него не распространяется", "AFTER_COMMIT");
        assertThat(violations.get(0).confidenceOrDefault()).isEqualTo(Confidence.PROBABLE);
        assertThat(violations.get(1).message()).contains("'indexer.reindex(...)'");
    }

    @Test
    void removalInForEachMaySilentlySkipLastElement() {
        List<Violation> violations = RuleTests.check(new CollectionModificationInForEachRule(), """
                class Sample {
                    void clean(List<Order> orders) {
                        for (Order order : orders) {
                            if (order.isCancelled()) {
                                orders.remove(order);
                            }
                        }
                    }
                    void grow(List<Order> orders) {
                        for (Order order : orders) {
                            orders.add(order.copy());
                        }
                    }
                    void lambda(List<Order> orders) {
                        orders.forEach(order -> orders.remove(order));
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(5, 11, 15);
        // Удаление предпоследнего элемента исключения не дает: цикл просто кончается раньше
        assertThat(violations.get(0).message())
                .contains("ConcurrentModificationException", "предпоследний элемент", "последний элемент останется")
                .contains("removeIf()");
        assertThat(violations.get(1).message()).doesNotContain("предпоследний");
        assertThat(violations.get(2).message()).doesNotContain("предпоследний");
    }
}
