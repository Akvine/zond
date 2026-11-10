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

    @Test
    void findsBreakAndContinueThatLeaveFinally() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    void skip(List<String> items) {
                        for (String item : items) {
                            try {
                                send(item);
                            } finally {
                                continue;
                            }
                        }
                    }
                    void stop(List<String> items) {
                        outer:
                        while (true) {
                            for (String item : items) {
                                try {
                                    send(item);
                                } finally {
                                    if (item.isEmpty()) {
                                        break outer;
                                    }
                                }
                            }
                        }
                    }
                    void ok(List<String> items) {
                        try {
                            send("first");
                        } finally {
                            for (String item : items) {
                                if (item.isEmpty()) {
                                    continue;
                                }
                                if (item.isBlank()) {
                                    break;
                                }
                                close(item);
                            }
                            switch (items.size()) {
                                case 0:
                                    break;
                                default:
                                    close("all");
                            }
                        }
                    }
                }
                """)).containsExactly(7, 19);
    }
}
