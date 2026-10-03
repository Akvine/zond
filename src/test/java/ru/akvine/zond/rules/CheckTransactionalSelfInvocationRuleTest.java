package ru.akvine.zond.rules;

import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckTransactionalSelfInvocationRuleTest {
    private final CheckTransactionalSelfInvocationRule rule = new CheckTransactionalSelfInvocationRule();

    @Test
    void findsCallsFromNonTransactionalMethods() {
        List<Violation> violations = rule.check(parse("""
                class Service {
                    public void process() {
                        save();
                        this.save();
                        items.forEach(item -> save());
                    }

                    @Transactional
                    public void save() {}
                }
                """));

        assertThat(violations).extracting(Violation::line).containsExactly(3, 4, 5);
        assertThat(violations).allMatch(violation -> violation.ruleCode().equals("jr:2"));
        assertThat(violations).allMatch(violation -> violation.errorLevel() == ErrorLevel.CRITICAL);
        assertThat(violations.get(0).message()).contains("'save'", "'process'", "не будет открыта");
    }

    @Test
    void findsIgnoredPropagationWhenCallerIsTransactional() {
        List<Violation> violations = rule.check(parse("""
                @Transactional
                class Service {
                    public void fromClassLevel() {
                        audit();
                        jakartaAudit();
                    }

                    @Transactional
                    public void fromMethodLevel() {
                        audit();
                    }

                    @Transactional(propagation = Propagation.REQUIRES_NEW)
                    public void audit() {}

                    @Transactional(Transactional.TxType.REQUIRES_NEW)
                    public void jakartaAudit() {}
                }
                """));

        assertThat(violations).extracting(Violation::line).containsExactly(4, 5, 10);
        assertThat(violations.get(0).message()).contains("REQUIRES_NEW не сработает");
    }

    @Test
    void ignoresValidCode() {
        List<Violation> violations = rule.check(parse("""
                class Service {
                    private Service self;
                    private Repository repository;

                    @Transactional
                    public void joinsExistingTransaction() {
                        save();
                    }

                    public void callsThroughProxy() {
                        self.save();
                        repository.save();
                    }

                    public void callsAnotherOverload() {
                        save(1);
                    }

                    public void callsNonTransactional() {
                        helper();
                    }

                    @Transactional(readOnly = true)
                    public void save() {}

                    public void save(int id) {}

                    private void helper() {}

                    class Nested {
                        void save() {}

                        void run() {
                            save();
                        }
                    }
                }
                """));

        assertThat(violations).isEmpty();
    }

    private SourceFile parse(String code) {
        return new SourceFile(Path.of("Service.java"), StaticJavaParser.parse(code));
    }
}
