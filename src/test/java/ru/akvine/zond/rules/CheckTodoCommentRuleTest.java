package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.CheckTodoCommentRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckTodoCommentRuleTest {
    private final CheckTodoCommentRule rule = new CheckTodoCommentRule();

    @Test
    void findsTodoAndFixmeComments() {
        List<Violation> violations = RuleTests.check(rule, """
                class Sample {
                    // TODO: remove
                    void run() {
                        /* FIXME broken */
                        work(); // regular comment
                    }
                    /**
                     * Describes todos and the TODOLIST.
                     */
                    void other() {
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(2, 4);
        assertThat(violations.get(0).message()).startsWith("TODO");
        assertThat(violations.get(1).message()).startsWith("FIXME");
    }
}
