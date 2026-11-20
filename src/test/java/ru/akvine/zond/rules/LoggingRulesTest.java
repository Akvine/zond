package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.performance.LogConcatenationRule;
import ru.akvine.zond.rules.performance.LoggingInLoopRule;
import ru.akvine.zond.rules.security.AuthorizationHeaderLoggingRule;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:93 - jr:95: логирование
 */
class LoggingRulesTest {

    @Test
    void logConcatenation() {
        // warn и error включены всегда: вне цикла подстановка там ничего не экономит
        assertThat(RuleTests.lines(new LogConcatenationRule(), """
                class Sample {
                    void run(Long id, Exception e) {
                        log.info("User " + id + " saved");
                        logger.debug(String.format("User %s", id));
                        LOG.error("Failed: " + e.getMessage(), e);
                        log.info("User {} saved", id);
                        log.info("Line one " + "line two");
                        printer.info("User " + id);
                    }
                    void runAll(List<Long> ids) {
                        for (Long id : ids) {
                            log.warn("Skipped " + id);
                        }
                    }
                }
                """)).containsExactly(3, 4, 12);
    }

    @Test
    void loggingInLoop() {
        assertThat(RuleTests.lines(new LoggingInLoopRule(), """
                class Sample {
                    void run(List<Order> orders) {
                        for (Order order : orders) {
                            log.info("Processing {}", order);
                            log.debug("Details {}", order);
                        }
                        orders.forEach(order -> log.warn("Bad {}", order));
                        log.info("Done {}", orders.size());
                    }
                }
                """)).containsExactly(4, 7);
    }

    @Test
    void authorizationHeaderLogging() {
        assertThat(RuleTests.lines(new AuthorizationHeaderLoggingRule(), """
                class Sample {
                    void run(HttpServletRequest request, String authHeader, HttpHeaders headers) {
                        log.info("Header {}", request.getHeader("Authorization"));
                        log.debug("Auth {}", authHeader);
                        log.info("Token {}", headers.getFirst(HttpHeaders.AUTHORIZATION));
                        log.warn("Authorization failed for {}", request.getRemoteUser());
                        log.info("Type {}", request.getHeader("Content-Type"));
                    }
                }
                """)).containsExactly(3, 4, 5);
    }
}
