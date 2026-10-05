package ru.akvine.zond.rules;

import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.logical.AutowiredOnStaticFieldRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AutowiredOnStaticFieldRuleTest {
    private final AutowiredOnStaticFieldRule rule = new AutowiredOnStaticFieldRule();

    @Test
    void findsAutowiredOnStaticFields() {
        List<Violation> violations = rule.check(parse("""
                class Service {
                    @Autowired
                    private static Repository repository;

                    @org.springframework.beans.factory.annotation.Autowired(required = false)
                    static Mapper first, second;

                    static class Nested {
                        @Autowired static Clock clock;
                    }
                }
                """));

        assertThat(violations).extracting(Violation::line).containsExactly(2, 5, 9);
        assertThat(violations).allMatch(violation -> violation.ruleCode().equals("jr:4"));
        assertThat(violations).allMatch(violation -> violation.errorLevel() == ErrorLevel.CRITICAL);
        assertThat(violations.get(0).message()).contains("'repository'");
        assertThat(violations.get(1).message()).contains("'first, second'");
    }

    @Test
    void ignoresValidCode() {
        List<Violation> violations = rule.check(parse("""
                class Service {
                    @Autowired
                    private Repository repository;

                    private static final Logger LOGGER = null;

                    @Deprecated
                    private static Mapper mapper;

                    @Autowired
                    public void setMapper(Mapper mapper) {}
                }
                """));

        assertThat(violations).isEmpty();
    }

    private SourceFile parse(String code) {
        return new SourceFile(Path.of("Service.java"), StaticJavaParser.parse(code));
    }
}
