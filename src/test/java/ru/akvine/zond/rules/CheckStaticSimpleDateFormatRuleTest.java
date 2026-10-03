package ru.akvine.zond.rules;

import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckStaticSimpleDateFormatRuleTest {
    private final CheckStaticSimpleDateFormatRule rule = new CheckStaticSimpleDateFormatRule();

    @Test
    void findsOnlyStaticSimpleDateFormatFields() {
        List<Violation> violations = rule.check(parse("""
                class Dates {
                    private static final SimpleDateFormat FORMAT = new SimpleDateFormat("yyyy");
                    static DateFormat OTHER = new java.text.SimpleDateFormat("yyyy");
                    static java.text.SimpleDateFormat declared;
                    private final SimpleDateFormat instance = new SimpleDateFormat("yyyy");
                    private static final ThreadLocal<SimpleDateFormat> SAFE = ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy"));
                    private static final DateTimeFormatter MODERN = DateTimeFormatter.ISO_DATE;
                    void local() { SimpleDateFormat local = new SimpleDateFormat("yyyy"); }
                }
                """));

        assertThat(violations).extracting(Violation::line).containsExactly(2, 3, 4);
        assertThat(violations).allMatch(violation -> violation.ruleCode().equals("jr:9"));
        assertThat(violations).allMatch(violation -> violation.errorLevel() == ErrorLevel.CRITICAL);
        assertThat(violations).allMatch(violation -> violation.errorType() == ErrorType.DATE_AND_TIME);
        assertThat(violations.get(0).message()).contains("'FORMAT'");
    }

    private SourceFile parse(String code) {
        return new SourceFile(Path.of("Dates.java"), StaticJavaParser.parse(code));
    }
}
