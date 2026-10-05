package ru.akvine.zond.rules;

import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.FieldInjectionRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FieldInjectionRuleTest {
    private final FieldInjectionRule rule = new FieldInjectionRule();

    @Test
    void findsFieldInjection() {
        List<Violation> violations = rule.check(parse("""
                @Service
                class OrderService {
                    @Autowired
                    private Repository repository;

                    @jakarta.inject.Inject
                    Mapper first, second;

                    @Resource(name = "clock")
                    private Clock clock;
                }
                """));

        assertThat(violations).extracting(Violation::line).containsExactly(3, 6, 9);
        assertThat(violations).allMatch(violation -> violation.ruleCode().equals("jr:6"));
        assertThat(violations).allMatch(violation -> violation.errorLevel() == ErrorLevel.MINOR);
        assertThat(violations.get(0).message()).contains("'repository'", "@Autowired");
        assertThat(violations.get(1).message()).contains("'first, second'", "@Inject");
    }

    @Test
    void ignoresValidCode() {
        List<Violation> violations = rule.check(parse("""
                @Service
                class OrderService {
                    private final Repository repository;

                    @Value("${timeout}")
                    private int timeout;

                    @Autowired
                    private static Mapper mapper;

                    @Autowired
                    OrderService(Repository repository) {
                        this.repository = repository;
                    }

                    @Autowired
                    void setClock(Clock clock) {}
                }
                """));

        assertThat(violations).isEmpty();
    }

    @Test
    void ignoresTestClasses() {
        List<Violation> violations = rule.check(parse("""
                @SpringBootTest
                class AnnotatedTest {
                    @Autowired
                    private Repository repository;

                    @Nested
                    class Inner {
                        @Autowired
                        private Mapper mapper;
                    }
                }

                class PlainTest {
                    @Autowired
                    private Repository repository;

                    @Test
                    void works() {}
                }
                """));

        assertThat(violations).isEmpty();
    }

    private SourceFile parse(String code) {
        return new SourceFile(Path.of("OrderService.java"), StaticJavaParser.parse(code));
    }
}
