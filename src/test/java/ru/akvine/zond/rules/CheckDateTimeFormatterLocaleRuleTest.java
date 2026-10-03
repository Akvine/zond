package ru.akvine.zond.rules;

import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckDateTimeFormatterLocaleRuleTest {
    private final CheckDateTimeFormatterLocaleRule rule = new CheckDateTimeFormatterLocaleRule();

    @Test
    void findsMissingAndMismatchedLocale() {
        List<Violation> violations = rule.check(parse("""
                class Formats {
                    DateTimeFormatter noLocale = DateTimeFormatter.ofPattern("d MMMM yyyy");
                    DateTimeFormatter wrong = DateTimeFormatter.ofPattern("d MMMM yyyy 'г.'", Locale.US);
                    DateTimeFormatter chained = DateTimeFormatter.ofPattern("EEEE, d MMMM 'года'").withLocale(Locale.ENGLISH);
                    DateTimeFormatter weekday = java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm");
                }
                """));

        assertThat(violations).extracting(Violation::line).containsExactly(2, 3, 4, 5);
        assertThat(violations).allMatch(violation -> violation.ruleCode().equals("jr:13"));
        assertThat(violations).allMatch(violation -> violation.errorLevel() == ErrorLevel.MAJOR);
        assertThat(violations.get(0).message()).contains("без локали");
        assertThat(violations.get(1).message()).contains("Locale.US", "русском");
        assertThat(violations.get(2).message()).contains("Locale.ENGLISH");
    }

    @Test
    void ignoresValidCode() {
        List<Violation> violations = rule.check(parse("""
                class Formats {
                    DateTimeFormatter numeric = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
                    DateTimeFormatter numericWithText = DateTimeFormatter.ofPattern("dd.MM.yyyy 'г.'", Locale.US);
                    DateTimeFormatter russian = DateTimeFormatter.ofPattern("d MMMM yyyy 'г.'", Locale.forLanguageTag("ru"));
                    DateTimeFormatter russianNew = DateTimeFormatter.ofPattern("d MMMM yyyy 'г.'", new Locale("ru"));
                    DateTimeFormatter english = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.US);
                    DateTimeFormatter chained = DateTimeFormatter.ofPattern("d MMMM yyyy").withLocale(RUSSIAN);
                    DateTimeFormatter quoted = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm 'a' 'E'");
                    DateTimeFormatter constant = DateTimeFormatter.ofPattern(PATTERN);
                    DateTimeFormatter iso = DateTimeFormatter.ISO_DATE;
                }
                """));

        assertThat(violations).isEmpty();
    }

    private SourceFile parse(String code) {
        return new SourceFile(Path.of("Formats.java"), StaticJavaParser.parse(code));
    }
}
