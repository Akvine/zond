package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.performance.StringConcatenationInLoopRule;

import static org.assertj.core.api.Assertions.assertThat;

class StringConcatenationInLoopRuleTest {
    private final StringConcatenationInLoopRule rule = new StringConcatenationInLoopRule();

    @Test
    void findsStringAccumulatedInLoop() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    String run(List<String> items) {
                        String result = "";
                        for (String item : items) {
                            result += item;
                        }
                        int i = 0;
                        while (i < 10) {
                            result = result + i;
                            i += 1;
                            String local = "";
                            local += i;
                        }
                        result += "end";
                        StringBuilder builder = new StringBuilder();
                        for (String item : items) {
                            builder.append(item);
                        }
                        return result;
                    }
                }
                """)).containsExactly(5, 9);
    }
}
