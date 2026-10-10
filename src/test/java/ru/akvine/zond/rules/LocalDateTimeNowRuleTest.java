package ru.akvine.zond.rules;

import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.datetime.LocalDateTimeNowRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalDateTimeNowRuleTest {
    private final LocalDateTimeNowRule rule = new LocalDateTimeNowRule();

    @Test
    void findsOnlyLocalDateTimeNowWithoutZone() {
        List<Violation> violations = rule.check(parse("""
                class Timestamps {
                    LocalDateTime created = LocalDateTime.now();
                    void run() {
                        var a = java.time.LocalDateTime.now();
                        var b = LocalDateTime.now(ZoneOffset.UTC);
                        var c = LocalDateTime.now(clock);
                        var d = ZonedDateTime.now();
                        var e = Instant.now();
                        var f = LocalDate.now();
                        var g = LocalDateTime.of(2020, 1, 1, 0, 0);
                    }
                }
                """));

        // Находка одна на файл: первое место и число остальных
        assertThat(violations).extracting(Violation::line).containsExactly(2);
        assertThat(violations.get(0).message()).contains("в этом файле еще 1 таких мест");
        assertThat(violations).allMatch(violation -> violation.ruleCode().equals("jr:10"));
        assertThat(violations).allMatch(violation -> violation.errorLevel() == ErrorLevel.MAJOR);
    }

    private SourceFile parse(String code) {
        return new SourceFile(Path.of("Timestamps.java"), StaticJavaParser.parse(code));
    }
}
