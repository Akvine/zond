package ru.akvine.zond.rules;

import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.datetime.CheckPeriodBetweenForExactIntervalRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckPeriodBetweenForExactIntervalRuleTest {
    private final CheckPeriodBetweenForExactIntervalRule rule = new CheckPeriodBetweenForExactIntervalRule();

    @Test
    void findsSingleComponentUsedAsInterval() {
        List<Violation> violations = rule.check(parse("""
                class Intervals {
                    long days(LocalDate a, LocalDate b) {
                        return Period.between(a, b).getDays();
                    }
                    int months(LocalDate a, LocalDate b) {
                        return java.time.Period.between(a, b).getMonths();
                    }
                    void variable(LocalDate a, LocalDate b) {
                        Period period = Period.between(a, b);
                        print(period.getMonths() + " " + period.getDays());
                    }
                }
                """));

        assertThat(violations).extracting(Violation::line).containsExactly(3, 6, 10);
        assertThat(violations).allMatch(violation -> violation.ruleCode().equals("jr:11"));
        assertThat(violations).allMatch(violation -> violation.errorLevel() == ErrorLevel.MAJOR);
        assertThat(violations.get(0).message()).contains("getDays()", "ChronoUnit.DAYS");
        assertThat(violations.get(2).message()).contains("getMonths()");
    }

    @Test
    void ignoresValidCode() {
        List<Violation> violations = rule.check(parse("""
                class Intervals {
                    String full(LocalDate a, LocalDate b) {
                        Period period = Period.between(a, b);
                        return period.getYears() + " " + period.getMonths() + " " + period.getDays();
                    }
                    Period returned(LocalDate a, LocalDate b) {
                        return Period.between(a, b);
                    }
                    void escapes(LocalDate a, LocalDate b) {
                        Period period = Period.between(a, b);
                        print(period.getDays());
                        send(period);
                    }
                    int years(LocalDate a, LocalDate b) {
                        return Period.between(a, b).getYears();
                    }
                    long totalMonths(LocalDate a, LocalDate b) {
                        return Period.between(a, b).toTotalMonths();
                    }
                    long exact(LocalDate a, LocalDate b) {
                        return ChronoUnit.DAYS.between(a, b);
                    }
                    long duration(Instant a, Instant b) {
                        return Duration.between(a, b).toDays();
                    }
                }
                """));

        assertThat(violations).isEmpty();
    }

    private SourceFile parse(String code) {
        return new SourceFile(Path.of("Intervals.java"), StaticJavaParser.parse(code));
    }
}
