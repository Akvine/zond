package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.performance.HttpCallInLoopRule;
import ru.akvine.zond.rules.performance.RepositoryCallInLoopRule;
import ru.akvine.zond.rules.streams.NonAssociativeReduceRule;
import ru.akvine.zond.rules.streams.PeekWithShortCircuitRule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:476 и jr:477 и ссылки на методы в jr:63 и jr:427: что стрим обещает выполнить, а что нет
 */
class StreamContractRulesTest {

    @Test
    void peekBeforeShortCircuitSeesOnlyPartOfElements() {
        List<Violation> violations = RuleTests.check(new PeekWithShortCircuitRule(), """
                class Sample {
                    Optional<Integer> first(List<Integer> values) {
                        return values.stream()
                                .peek(System.out::println)
                                .filter(x -> x > 2)
                                .findFirst();
                    }
                    boolean any(List<Order> orders) {
                        return orders.stream().peek(order -> order.setChecked(true)).anyMatch(Order::isPaid);
                    }
                    long counted(List<Order> orders) {
                        return orders.stream().peek(order -> audit.record(order)).count();
                    }
                    List<Order> limited(List<Order> orders) {
                        return orders.stream().peek(order -> log.debug("order {}", order.getId())).limit(10).toList();
                    }
                    long filtered(List<Order> orders) {
                        return orders.stream().filter(Order::isPaid).peek(order -> audit.record(order)).count();
                    }
                    boolean sorted(List<Integer> values) {
                        return values.stream().peek(System.out::println).sorted().anyMatch(x -> x > 2);
                    }
                    List<Order> all(List<Order> orders) {
                        return orders.stream().limit(10).peek(order -> audit.record(order)).toList();
                    }
                    Order head(Deque<Order> queue) {
                        return queue.peek();
                    }
                }
                """);

        // После sorted() и перед toList() peek видит все элементы; count() с filter проходит стрим честно
        assertThat(violations).extracting(Violation::line).containsExactly(4, 9, 12, 15);
        assertThat(violations.get(0).message()).contains("'peek(System.out::println)'", "перед findFirst()", "ленив");
        // Отладочный вывод - замечание, действие с данными - строже
        assertThat(violations.get(0).errorLevel()).isEqualTo(ErrorLevel.INFO);
        assertThat(violations.get(1).errorLevel()).isEqualTo(ErrorLevel.MINOR);
        assertThat(violations.get(2).message()).contains("перед count()", "ни разу");
        assertThat(violations.get(3).errorLevel()).isEqualTo(ErrorLevel.INFO);
    }

    @Test
    void reduceMustSurviveParallelExecution() {
        List<Violation> violations = RuleTests.check(new NonAssociativeReduceRule(), """
                class Sample {
                    int difference() {
                        return Stream.of(10, 5, 1).parallel().reduce(100, (a, b) -> a - b);
                    }
                    BigDecimal rest(List<BigDecimal> payments) {
                        return payments.parallelStream().reduce(BigDecimal::subtract).orElseThrow();
                    }
                    int sequential(List<Integer> values) {
                        return values.stream().reduce(100, (a, b) -> a - b);
                    }
                    int shifted(List<Integer> values) {
                        return values.parallelStream().reduce(10, Integer::sum);
                    }
                    String joined(List<String> names) {
                        return names.parallelStream()
                                .reduce(new StringBuilder(), (builder, name) -> builder.append(name), StringBuilder::append)
                                .toString();
                    }
                    int good(List<Integer> values) {
                        int base = values.stream().reduce(10, Integer::sum);
                        int product = values.parallelStream().reduce(1, (a, b) -> a * b);
                        BigDecimal total = prices.parallelStream().reduce(BigDecimal.ZERO, BigDecimal::add);
                        Integer max = values.parallelStream().reduce((a, b) -> a > b ? a : b).orElse(0);
                        return 100 - values.parallelStream().reduce(0, Integer::sum);
                    }
                    Mono<Integer> reactive(Flux<Integer> values) {
                        return values.reduce((a, b) -> a - b);
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(3, 6, 9, 12, 16);
        assertThat(violations.get(0).message())
                .contains("неассоциативной операцией ('a - b')", "в параллельном стриме", "(a - b) - c");
        assertThat(violations.get(0).errorLevel()).isEqualTo(ErrorLevel.MAJOR);
        assertThat(violations.get(1).message()).contains("BigDecimal::subtract");
        // Последовательный стрим дает один и тот же ответ, пока к нему не добавят parallel()
        assertThat(violations.get(2).message()).contains("пока стрим последовательный");
        assertThat(violations.get(2).errorLevel()).isEqualTo(ErrorLevel.MINOR);
        assertThat(violations.get(3).message()).contains("reduce(10, ...)", "нейтральным");
        assertThat(violations.get(4).message()).contains("изменяемый объект 'new StringBuilder()'", "collect()");
    }

    @Test
    void methodReferenceInStreamCallsExternalSystemPerElement() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("PriceClient.java", """
                @Component
                class PriceClient {
                    private final RestTemplate restTemplate;
                    Price fetch(Long id) {
                        return restTemplate.getForObject("http://prices/" + id, Price.class);
                    }
                    Price convert(Long id) {
                        return new Price(id);
                    }
                }
                """);
        files.put("RatesApi.java", """
                @FeignClient(name = "rates")
                interface RatesApi {
                    @GetMapping("/rates/{code}")
                    Rate rate(@PathVariable String code);
                }
                """);
        files.put("PriceService.java", """
                @Service
                class PriceService {
                    private final PriceClient priceClient;
                    private final RatesApi ratesApi;
                    private final ProductRepository productRepository;
                    List<Price> prices(List<Long> ids) {
                        return ids.stream().map(priceClient::fetch).toList();
                    }
                    List<Rate> rates(List<String> codes) {
                        return codes.stream().map(ratesApi::rate).toList();
                    }
                    List<Product> products(List<Long> ids) {
                        return ids.stream().map(productRepository::getReferenceById).toList();
                    }
                    void audit(List<Product> products) {
                        products.forEach(productRepository::save);
                    }
                    List<Price> local(List<Long> ids) {
                        return ids.stream().map(priceClient::convert).map(Price::rounded).toList();
                    }
                    Optional<Price> single(Optional<Long> id) {
                        return id.map(priceClient::fetch);
                    }
                }
                """);

        List<Violation> http = RuleTests.checkProject(new HttpCallInLoopRule(), files);
        // Optional.map вызывает метод один раз, а convert в сеть не ходит
        assertThat(http).extracting(Violation::line).containsExactly(7, 10);
        assertThat(http.get(0).message())
                .contains("Ссылка на метод 'priceClient::fetch' в операции map(...)", "restTemplate.getForObject");
        assertThat(http.get(0).confidenceOrDefault()).isEqualTo(Confidence.PROBABLE);
        assertThat(http.get(1).message()).contains("Сетевой вызов по ссылке 'ratesApi::rate'");

        List<Violation> database = RuleTests.checkProject(new RepositoryCallInLoopRule(), files);
        assertThat(database).extracting(Violation::line).containsExactly(13, 16);
        assertThat(database.get(0).message())
                .contains("по ссылке 'productRepository::getReferenceById' в операции map(...)", "N+1");
    }
}
