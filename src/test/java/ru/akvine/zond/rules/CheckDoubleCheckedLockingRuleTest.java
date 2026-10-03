package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CheckDoubleCheckedLockingRuleTest {
    private final CheckDoubleCheckedLockingRule rule = new CheckDoubleCheckedLockingRule();

    @Test
    void findsDoubleCheckedLockingWithoutVolatile() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    private static Sample instance;
                    private static volatile Sample safe;
                    static Sample get() {
                        if (instance == null) {
                            synchronized (Sample.class) {
                                if (instance == null) {
                                    instance = new Sample();
                                }
                            }
                        }
                        return instance;
                    }
                    static Sample getSafe() {
                        if (safe == null) {
                            synchronized (Sample.class) {
                                if (safe == null) {
                                    safe = new Sample();
                                }
                            }
                        }
                        return safe;
                    }
                    static Sample single() {
                        if (instance == null) {
                            instance = new Sample();
                        }
                        return instance;
                    }
                }
                """)).containsExactly(5);
    }
}
