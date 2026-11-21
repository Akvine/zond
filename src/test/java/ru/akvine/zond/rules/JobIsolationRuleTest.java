package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.logical.BatchWithoutErrorIsolationRule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jr:548: задача по расписанию, в которой сбой внешнего вызова на одной записи обрывает обработку остальных
 */
class JobIsolationRuleTest {
    private static final String REPOSITORY = """
            public interface AdvertRepository extends JpaRepository<Advert, Long> {
                List<Advert> findByStatus(String status);
                List<Advert> findByStatusOrderByIdAsc(String status);
                @Query("select a from Advert a where a.status = 'NEW' order by a.created")
                List<Advert> findQueue();
            }
            """;
    private static final String CLIENT = """
            @Service
            public class MarketClient {
                private final RestTemplate restTemplate;
                // исключение наружу: ловят, чтобы завернуть в свое
                public Budget budget(Advert advert) {
                    try {
                        return restTemplate.getForObject("/budget/" + advert.getId(), Budget.class);
                    } catch (Exception exception) {
                        throw new IntegrationException(exception.getMessage());
                    }
                }
                // отказ гасят на месте
                public Budget quietBudget(Advert advert) {
                    try {
                        return restTemplate.getForObject("/budget/" + advert.getId(), Budget.class);
                    } catch (Exception exception) {
                        log.warn("no budget for {}", advert.getId(), exception);
                        return null;
                    }
                }
                @Async
                public void notifyOwner(Advert advert) {
                    restTemplate.postForObject("/notify", advert, Void.class);
                }
            }
            """;

    private static List<Violation> check(String... jobs) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("AdvertRepository.java", REPOSITORY);
        files.put("MarketClient.java", CLIENT);
        for (int index = 0; index < jobs.length; index++) {
            files.put("Job" + index + ".java", jobs[index]);
        }
        return RuleTests.checkProject(new BatchWithoutErrorIsolationRule(), files);
    }

    private static String job(String body) {
        return """
                public class AdvertJob {
                    private final AdvertRepository advertRepository;
                    private final MarketClient marketClient;
                    private final RestTemplate restTemplate;
                    private final KafkaTemplate<String, Advert> kafkaTemplate;
                    private final List<Advert> cached = new ArrayList<>();
                    @Scheduled(fixedDelay = 60000)
                    public void check() {
                %s
                    }
                }
                """.formatted(body.indent(8).stripTrailing());
    }

    @Test
    void remoteCallPerRecordWithoutIsolationIsFound() {
        List<Violation> violations = check(job("""
                List<Advert> adverts = advertRepository.findByStatus("RUNNING");
                for (Advert advert : adverts) {
                    Budget budget = marketClient.budget(advert);
                    advert.setBudget(budget.getTotal());
                    advertRepository.save(advert);
                }
                """));
        assertThat(violations).extracting(Violation::line).containsExactly(10);
        assertThat(violations.get(0).message()).contains("'check'", "'advertRepository.findByStatus'",
                "'restTemplate.getForObject'", "через вызов budget", "обрывает проход");
        assertThat(violations.get(0).confidenceOrDefault()).isEqualTo(Confidence.PROBABLE);

        // Прямой запрос в поэлементной операции; try вокруг всего цикла проход не спасает
        assertThat(check(job("""
                try {
                    advertRepository.findByStatus("RUNNING").stream()
                            .filter(Advert::isActive)
                            .forEach(advert -> restTemplate.postForObject("/pause", advert, Void.class));
                } catch (Exception exception) {
                    log.error("check failed", exception);
                }
                """))).extracting(Violation::line).containsExactly(10);
    }

    @Test
    void loopInMethodCalledByJobIsFound() {
        List<Violation> violations = check("""
                public class AdvertJob {
                    private final AdvertProcessor processor;
                    @Scheduled(cron = "0 * * * * *")
                    public void run() {
                        log.info("start");
                        processor.processAll();
                    }
                }
                """, """
                @Service
                public class AdvertProcessor {
                    private final AdvertRepository advertRepository;
                    private final MarketClient marketClient;
                    public void processAll() {
                        for (Advert advert : advertRepository.findByStatus("RUNNING")) {
                            marketClient.budget(advert);
                        }
                    }
                    // Этот метод задача не вызывает
                    public void manual() {
                        for (Advert advert : advertRepository.findByStatus("RUNNING")) {
                            marketClient.budget(advert);
                        }
                    }
                }
                """);
        assertThat(violations).extracting(violation -> violation.file().getFileName() + ":" + violation.line())
                .containsExactly("Job1.java:6");
        assertThat(violations.get(0).message()).contains("'run'", "цикл в методе 'processAll'");
    }

    @Test
    void isolatedOrAbsorbedFailureIsFine() {
        // Обработка одной записи в try-catch
        assertThat(check(job("""
                for (Advert advert : advertRepository.findByStatus("RUNNING")) {
                    try {
                        marketClient.budget(advert);
                    } catch (Exception exception) {
                        log.error("advert {} failed", advert.getId(), exception);
                    }
                }
                """))).isEmpty();
        // Отказ гасит вызванный метод; @Async выполняется в другом потоке; лямбду выполнит пул
        assertThat(check(job("""
                for (Advert advert : advertRepository.findByStatus("RUNNING")) {
                    marketClient.quietBudget(advert);
                    marketClient.notifyOwner(advert);
                    executor.submit(() -> restTemplate.postForObject("/pause", advert, Void.class));
                }
                """))).isEmpty();
        // Отправка в Kafka ответа не ждет
        assertThat(check(job("""
                for (Advert advert : advertRepository.findByStatus("RUNNING")) {
                    kafkaTemplate.send("adverts", advert);
                }
                """))).isEmpty();
    }

    @Test
    void transactionOrOtherSourceIsNotJudged() {
        // Перебирают не записи из базы
        assertThat(check(job("""
                for (Advert advert : cached) {
                    marketClient.budget(advert);
                }
                for (String region : List.of("ru", "kz")) {
                    restTemplate.getForObject("/regions/" + region, Region.class);
                }
                """))).isEmpty();
        // Весь проход - одна транзакция: остановка на первой ошибке задумана
        assertThat(check("""
                public class AdvertJob {
                    private final AdvertRepository advertRepository;
                    private final MarketClient marketClient;
                    @Transactional
                    @Scheduled(fixedDelay = 60000)
                    public void check() {
                        for (Advert advert : advertRepository.findByStatus("RUNNING")) {
                            marketClient.budget(advert);
                        }
                    }
                }
                """)).isEmpty();
        // Не задача по расписанию
        assertThat(check("""
                @Service
                public class AdvertService {
                    private final AdvertRepository advertRepository;
                    private final MarketClient marketClient;
                    public void refresh() {
                        for (Advert advert : advertRepository.findByStatus("RUNNING")) {
                            marketClient.budget(advert);
                        }
                    }
                }
                """)).isEmpty();
    }

    @Test
    void orderedQueueIsOnlySuspicion() {
        // Очередь читают по порядку: остановиться на первой ошибке могли намеренно
        List<Violation> byName = check(job("""
                for (Advert advert : advertRepository.findByStatusOrderByIdAsc("NEW")) {
                    marketClient.budget(advert);
                }
                """));
        assertThat(byName).hasSize(1);
        assertThat(byName.get(0).confidenceOrDefault()).isEqualTo(Confidence.SUSPICION);
    }
}
