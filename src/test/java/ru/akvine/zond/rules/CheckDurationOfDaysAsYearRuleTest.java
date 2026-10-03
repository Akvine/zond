package ru.akvine.zond.rules;

import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckDurationOfDaysAsYearRuleTest {
    private final CheckDurationOfDaysAsYearRule rule = new CheckDurationOfDaysAsYearRule();

    @Test
    void findsOnlyYearLengthInDays() {
        List<Violation> violations = rule.check(parse("""
                class Years {
                    Duration year = Duration.ofDays(365);
                    Duration many(int years) {
                        return Duration.ofDays(365).multipliedBy(years);
                    }
                    Duration product(int years) {
                        return java.time.Duration.ofDays(years * 365L);
                    }
                    Duration leap = Duration.ofDays((366));
                    Duration week = Duration.ofDays(7);
                    Duration variable = Duration.ofDays(days);
                    Duration sum = Duration.ofDays(365 + 1);
                    Period period = Period.ofDays(365);
                    Duration hours = Duration.ofHours(365);
                }
                """));

        assertThat(violations).extracting(Violation::line).containsExactly(2, 4, 7, 9);
        assertThat(violations).allMatch(violation -> violation.ruleCode().equals("jr:12"));
        assertThat(violations).allMatch(violation -> violation.errorLevel() == ErrorLevel.MAJOR);
        assertThat(violations.get(2).message()).contains("years * 365L");
    }

    private SourceFile parse(String code) {
        return new SourceFile(Path.of("Years.java"), StaticJavaParser.parse(code));
    }
}
