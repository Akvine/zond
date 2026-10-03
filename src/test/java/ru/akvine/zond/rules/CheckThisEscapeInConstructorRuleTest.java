package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.Violation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckThisEscapeInConstructorRuleTest {
    private final CheckThisEscapeInConstructorRule rule = new CheckThisEscapeInConstructorRule();

    @Test
    void findsThisLeavingConstructor() {
        List<Violation> violations = RuleTests.check(rule, """
                class Sample {
                    private final List<Listener> listeners = new ArrayList<>();
                    private static Sample instance;
                    private Runnable callback;
                    Sample(EventBus bus, Other other, ExecutorService executor) {
                        bus.register(this);
                        new Thread(this).start();
                        bus.subscribe(this::handle);
                        other.callback = this::handle;
                        instance = this;
                        executor.submit(() -> this.handle());
                        this.callback = this::handle;
                        callback = other::run;
                        listeners.add(this);
                        this.name = other.name();
                        Runnable local = this::handle;
                        log(this.name);
                    }
                    void later(EventBus bus) {
                        bus.register(this);
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(6, 7, 8, 9, 10, 11);
        assertThat(violations).allMatch(violation -> violation.ruleCode().equals("jr:43"));
        assertThat(violations).allMatch(violation -> violation.errorType() == ErrorType.CONCURRENCY);
        assertThat(violations.get(0).message()).contains("'Sample'", "bus.register(this)");
    }

    @Test
    void reportsLambdaOnceAndIgnoresAnonymousClasses() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    Sample(ExecutorService executor) {
                        executor.submit(() -> {
                            this.first();
                            this.second();
                        });
                        Runnable task = new Runnable() {
                            public void run() {
                                registry.add(this);
                            }
                        };
                    }
                }
                """)).containsExactly(3);
    }
}
