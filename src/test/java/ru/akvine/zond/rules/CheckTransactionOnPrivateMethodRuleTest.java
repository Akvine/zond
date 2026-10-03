package ru.akvine.zond.rules;

import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckTransactionOnPrivateMethodRuleTest {
    private final CheckTransactionOnPrivateMethodRule rule = new CheckTransactionOnPrivateMethodRule();

    @Test
    void findsTransactionalOnPrivateMethods() {
        List<Violation> violations = rule.check(parse("""
                class Service {
                    @Transactional
                    private void simple() {}

                    @org.springframework.transaction.annotation.Transactional(readOnly = true)
                    private void fullyQualified() {}
                }
                """));

        assertThat(violations).extracting(Violation::line).containsExactly(2, 5);
        assertThat(violations).allMatch(violation -> violation.ruleCode().equals("jr:1"));
        assertThat(violations.get(0).message()).contains("simple");
    }

    @Test
    void ignoresValidCode() {
        List<Violation> violations = rule.check(parse("""
                @Transactional
                class Service {
                    @Transactional
                    public void publicMethod() {}

                    @Transactional
                    void packagePrivateMethod() {}

                    @Deprecated
                    private void privateWithoutTransactional() {}
                }
                """));

        assertThat(violations).isEmpty();
    }

    private SourceFile parse(String code) {
        return new SourceFile(Path.of("Service.java"), StaticJavaParser.parse(code));
    }
}
