package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.streams.CollectUnboundedStreamRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CollectUnboundedStreamRuleTest {
    private final CollectUnboundedStreamRule rule = new CollectUnboundedStreamRule();

    @Test
    void findsUnboundedStreamsCollectedIntoMemory() {
        List<Violation> violations = RuleTests.check(rule, """
                class Sample {
                    void run(List<String> list, Path path, BufferedReader reader) throws IOException {
                        List<String> a = list.parallelStream().collect(Collectors.toList());
                        List<String> b = Files.lines(path).collect(toList());
                        List<Integer> c = Stream.iterate(1, i -> i + 1).toList();
                        List<String> d = reader.lines().map(String::trim).toList();
                        Set<User> e = repository.findAll().stream().collect(Collectors.toSet());
                        List<String> ok1 = list.stream().map(String::trim).collect(Collectors.toList());
                        List<String> ok2 = Files.lines(path).limit(100).collect(toList());
                        List<Integer> ok3 = Stream.iterate(1, i -> i < 10, i -> i + 1).toList();
                        long ok4 = Files.lines(path).count();
                        List<Integer> ok5 = Stream.generate(() -> 1).limit(5).toList();
                        Object[] ok6 = list.toArray();
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(3, 4, 5, 6, 7);
        assertThat(violations).allMatch(violation -> violation.ruleCode().equals("jr:52"));
        assertThat(violations.get(0).message()).contains("параллельного стрима");
        assertThat(violations.get(1).message()).contains("Files.lines");
        assertThat(violations.get(2).message()).contains("бесконечного источника");
    }
}
