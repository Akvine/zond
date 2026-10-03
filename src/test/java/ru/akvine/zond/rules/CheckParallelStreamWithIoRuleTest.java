package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckParallelStreamWithIoRuleTest {
    private final CheckParallelStreamWithIoRule rule = new CheckParallelStreamWithIoRule();

    @Test
    void findsIoInsideParallelStreams() {
        List<Violation> violations = RuleTests.check(rule, """
                class Sample {
                    void run(List<Long> ids, List<Path> paths, List<String> files) {
                        ids.parallelStream().map(id -> userRepository.findById(id)).toList();
                        paths.stream().parallel().map(path -> Files.readString(path)).toList();
                        ids.parallelStream().forEach(id -> restTemplate.getForObject(url, String.class, id));
                        paths.parallelStream().map(Files::readAllBytes).toList();
                        ids.parallelStream().map(id -> id * 2).filter(id -> id > 10).toList();
                        ids.stream().map(id -> userRepository.findById(id)).toList();
                        ids.parallelStream().map(mapper::toDto).toList();
                        ids.parallelStream().filter(id -> files.contains(id)).toList();
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(3, 4, 5, 6);
        assertThat(violations).allMatch(violation -> violation.ruleCode().equals("jr:50"));
        assertThat(violations.get(0).message()).contains("userRepository.findById");
        assertThat(violations.get(3).message()).contains("Files::readAllBytes");
    }
}
