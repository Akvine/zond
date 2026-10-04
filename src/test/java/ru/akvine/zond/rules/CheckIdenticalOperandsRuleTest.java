package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.logical.CheckIdenticalOperandsRule;

import static org.assertj.core.api.Assertions.assertThat;

class CheckIdenticalOperandsRuleTest {
    private final CheckIdenticalOperandsRule rule = new CheckIdenticalOperandsRule();

    @Test
    void findsOperationsWithIdenticalOperands() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    boolean run(int a, int b, boolean flag) {
                        boolean r1 = a == a;
                        boolean r2 = flag && flag;
                        int r3 = a - a;
                        boolean r4 = (a + b) > (a + b);
                        boolean ok1 = a == b;
                        int ok2 = a + a;
                        int ok3 = a * a;
                        boolean ok4 = next() == next();
                        int ok5 = 1 - 1;
                        return r1;
                    }
                }
                """)).containsExactly(3, 4, 5, 6);
    }
}
