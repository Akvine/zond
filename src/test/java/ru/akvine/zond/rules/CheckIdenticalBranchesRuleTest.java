package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CheckIdenticalBranchesRuleTest {
    private final CheckIdenticalBranchesRule rule = new CheckIdenticalBranchesRule();

    @Test
    void findsIdenticalBranchesAndRepeatedConditions() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    int run(int a, int b) {
                        if (a > b) {
                            save(a);
                        } else {
                            save(a); // тот же вызов
                        }
                        if (a == 1) {
                            return 1;
                        } else if (a == 2) {
                            return 2;
                        } else if (a == 1) {
                            return 3;
                        }
                        int max = a > b ? a : a;
                        if (a > b) {
                            save(a);
                        } else {
                            save(b);
                        }
                        return a > b ? a : b;
                    }
                }
                """)).containsExactly(3, 12, 15);
    }
}
