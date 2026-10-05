package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.exceptions.JumpInFinallyRule;

import static org.assertj.core.api.Assertions.assertThat;

class JumpInFinallyRuleTest {
    private final JumpInFinallyRule rule = new JumpInFinallyRule();

    @Test
    void findsReturnAndThrowInFinally() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    int value() {
                        try {
                            return 1;
                        } finally {
                            return 2;
                        }
                    }
                    void fail() {
                        try {
                            work();
                        } finally {
                            throw new IllegalStateException();
                        }
                    }
                    void ok() {
                        try {
                            work();
                        } finally {
                            items.forEach(item -> { return; });
                            try {
                                throw new IllegalStateException();
                            } catch (RuntimeException e) {
                                log(e);
                            }
                            close();
                        }
                    }
                }
                """)).containsExactly(6, 13);
    }
}
