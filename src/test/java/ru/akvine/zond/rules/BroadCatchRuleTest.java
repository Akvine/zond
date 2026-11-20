package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.exceptions.BroadCatchRule;

import static org.assertj.core.api.Assertions.assertThat;

class BroadCatchRuleTest {
    private final BroadCatchRule rule = new BroadCatchRule();

    @Test
    void findsBroadCatchThatLosesException() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    void run() {
                        try {
                            work();
                        } catch (Exception e) {
                            log(e.getMessage());
                        }
                        try {
                            work();
                        } catch (IOException | Throwable e) {
                            count();
                        }
                        try {
                            work();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                        try {
                            work();
                        } catch (IOException e) {
                            log(e.getMessage());
                        }
                        try {
                            work();
                        } catch (Exception e) {
                        }
                    }
                }
                """)).containsExactly(5, 10);
    }

    @Test
    void exceptionPassedOnIsNotLost() {
        // Исключение целиком ушло в лог, обработчику, в результат либо в цепочку Reactor: оно не потеряно
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    private static final Logger log = LoggerFactory.getLogger(Sample.class);
                    void logged() {
                        try {
                            work();
                        } catch (Exception e) {
                            log.error("Work failed", e);
                        }
                    }
                    void handled() {
                        try {
                            work();
                        } catch (Exception e) {
                            errorHandler.handle(e);
                        }
                    }
                    Mono<String> reactive() {
                        try {
                            return Mono.just(work());
                        } catch (Exception ex) {
                            release();
                            return Mono.error(ex);
                        }
                    }
                    Result wrapped() {
                        try {
                            return Result.ok(work());
                        } catch (Exception e) {
                            return new Result(Status.FAILED, e);
                        }
                    }
                    String textOnly() {
                        try {
                            return work();
                        } catch (Exception e) {
                            log.error("Work failed: {}", e.getMessage());
                            return null;
                        }
                    }
                }
                """)).containsExactly(35);
    }
}
