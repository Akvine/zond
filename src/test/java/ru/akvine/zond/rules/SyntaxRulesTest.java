package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.logical.AssignmentInConditionRule;
import ru.akvine.zond.rules.logical.DeadCodeRule;
import ru.akvine.zond.rules.logical.EmptyStatementRule;
import ru.akvine.zond.rules.logical.FileNameMismatchRule;
import ru.akvine.zond.rules.logical.MisleadingIndentationRule;
import ru.akvine.zond.rules.logical.PackageMismatchRule;
import ru.akvine.zond.rules.logical.SwitchFallThroughRule;
import ru.akvine.zond.rules.logical.SwitchWithoutDefaultRule;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:115 - jr:122: подозрительный синтаксис и структура файла
 */
class SyntaxRulesTest {

    @Test
    void emptyStatement() {
        assertThat(RuleTests.lines(new EmptyStatementRule(), """
                class Sample {
                    void run(boolean ready, List<String> items) {
                        if (ready);
                        {
                            start();
                        }
                        for (String item : items);
                        while (ready);
                        if (ready) {
                            start();
                        }
                    }
                }
                """)).containsExactly(3, 7, 8);
    }

    @Test
    void assignmentInCondition() {
        assertThat(RuleTests.lines(new AssignmentInConditionRule(), """
                class Sample {
                    void run(boolean flag, BufferedReader reader) throws Exception {
                        if (flag = true) {
                            start();
                        }
                        String line;
                        while ((line = reader.readLine()) != null) {
                            use(line);
                        }
                        int value = (flag = false) ? 1 : 2;
                        if (flag == true) {
                            start();
                        }
                    }
                }
                """)).containsExactly(3, 10);
    }

    @Test
    void switchFallThrough() {
        assertThat(RuleTests.lines(new SwitchFallThroughRule(), """
                class Sample {
                    void run(int code) {
                        switch (code) {
                            case 1:
                                first();
                            case 2:
                                second();
                                break;
                            case 3:
                            case 4:
                                third();
                                // fall through
                            case 5:
                                fourth();
                                return;
                            default:
                                other();
                        }
                        switch (code) {
                            case 1 -> first();
                            default -> other();
                        }
                    }
                }
                """)).containsExactly(4);
    }

    @Test
    void misleadingIndentation() {
        assertThat(RuleTests.lines(new MisleadingIndentationRule(), """
                class Sample {
                    void run(boolean ready) {
                        if (ready)
                            first();
                            second();
                        for (int i = 0; i < 3; i++)
                            third();
                        fourth();
                        if (ready) {
                            first();
                        }
                        second();
                    }
                }
                """)).containsExactly(5);
    }

    @Test
    void deadCode() {
        assertThat(RuleTests.lines(new DeadCodeRule(), """
                class Sample {
                    int run(boolean ready) {
                        if (false) {
                            start();
                        }
                        while (false) {
                            start();
                        }
                        while (true) {
                            if (ready) {
                                break;
                            }
                        }
                        return 1;
                    }
                    void after() {
                        return;
                        start();
                    }
                }
                """)).containsExactly(3, 6, 18);
    }

    @Test
    void switchWithoutDefault() {
        assertThat(RuleTests.lines(new SwitchWithoutDefaultRule(), """
                class Sample {
                    int run(int code, Status status) {
                        switch (code) {
                            case 1:
                                return 1;
                        }
                        switch (code) {
                            case 1 -> first();
                            default -> other();
                        }
                        return switch (status) {
                            case NEW -> 1;
                            case DONE -> 2;
                        };
                    }
                }
                """)).containsExactly(3);
    }

    @Test
    void fileNameMismatch() {
        FileNameMismatchRule rule = new FileNameMismatchRule();

        assertThat(RuleTests.lines(rule, """
                public class Other {
                }
                class Helper {
                }
                """)).containsExactly(1);
        assertThat(RuleTests.lines(rule, """
                public class Sample {
                    public static class Nested {
                    }
                }
                """)).isEmpty();
    }

    @Test
    void packageMismatch() {
        PackageMismatchRule rule = new PackageMismatchRule();
        Path path = Path.of("src", "main", "java", "com", "example", "app", "Sample.java");

        assertThat(RuleTests.check(rule, path, "package com.example.app;\nclass Sample {}")).isEmpty();
        assertThat(RuleTests.check(rule, path, "package com.example.other;\nclass Sample {}"))
                .singleElement()
                .satisfies(violation -> assertThat(violation.message()).contains("com.example.other"));
        assertThat(RuleTests.check(rule, path, "class Sample {}")).isEmpty();
    }
}
