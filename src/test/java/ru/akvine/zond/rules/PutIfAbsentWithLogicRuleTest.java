package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.rules.concurrency.PutIfAbsentWithLogicRule;

import static org.assertj.core.api.Assertions.assertThat;

class PutIfAbsentWithLogicRuleTest {
    private final PutIfAbsentWithLogicRule rule = new PutIfAbsentWithLogicRule();

    @Test
    void findsLogicBranchingOnPutIfAbsent() {
        assertThat(RuleTests.lines(rule, """
                class Sample {
                    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
                    private final Map<String, Session> plain = new HashMap<>();
                    void bad(ConcurrentMap<String, Session> cache, String key, Session session) {
                        if (sessions.putIfAbsent(key, session) == null) {
                            init(session);
                        }
                        Session previous = cache.putIfAbsent(key, session);
                        if (previous != null) {
                            previous.close();
                        }
                    }
                    void good(String key, Session session) {
                        sessions.putIfAbsent(key, session);
                        Session created = sessions.computeIfAbsent(key, this::create);
                        if (plain.putIfAbsent(key, session) == null) {
                            init(session);
                        }
                    }
                }
                """)).containsExactly(5, 9);
    }
}
