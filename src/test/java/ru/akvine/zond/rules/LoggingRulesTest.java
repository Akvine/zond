package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:93 - jr:95: логирование
 */
class LoggingRulesTest {

    @Test
    void logConcatenation() {
        assertThat(RuleTests.lines(new CheckLogConcatenationRule(), """
                class Sample {
                    void run(Long id, Exception e) {
                        log.info("User " + id + " saved");
                        logger.debug(String.format("User %s", id));
                        LOG.error("Failed: " + e.getMessage(), e);
                        log.info("User {} saved", id);
                        log.info("Line one " + "line two");
                        printer.info("User " + id);
                    }
                }
                """)).containsExactly(3, 4, 5);
    }

    @Test
    void loggingInLoop() {
        assertThat(RuleTests.lines(new CheckLoggingInLoopRule(), """
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
        assertThat(RuleTests.lines(new CheckAuthorizationHeaderLoggingRule(), """
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
