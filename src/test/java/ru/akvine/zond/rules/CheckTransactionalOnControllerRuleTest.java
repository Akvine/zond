package ru.akvine.zond.rules;

import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.CheckTransactionalOnControllerRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckTransactionalOnControllerRuleTest {
    private final CheckTransactionalOnControllerRule rule = new CheckTransactionalOnControllerRule();

    @Test
    void findsTransactionalOnControllerAndItsMethods() {
        List<Violation> violations = rule.check(parse("""
                @RestController
                @Transactional
                class OrderController {
                    @GetMapping
                    public void list() {}

                    @PostMapping
                    @Transactional(readOnly = true)
                    public void create() {}
                }

                @org.springframework.stereotype.Controller
                class PageController {
                    @Transactional
                    String page() { return "page"; }
                }
                """));

        assertThat(violations).extracting(Violation::line).containsExactly(1, 7, 14);
        assertThat(violations).allMatch(violation -> violation.ruleCode().equals("jr:5"));
        assertThat(violations).allMatch(violation -> violation.errorLevel() == ErrorLevel.MINOR);
        assertThat(violations.get(0).message()).contains("контроллером 'OrderController'");
        assertThat(violations.get(1).message()).contains("'create'", "'OrderController'");
    }

    @Test
    void ignoresValidCode() {
        List<Violation> violations = rule.check(parse("""
                @RestController
                class CleanController {
                    @GetMapping
                    public void list() {}

                    @Transactional
                    private void privateMethod() {}

                    static class Helper {
                        @Transactional
                        public void save() {}
                    }
                }

                @Service
                @Transactional
                class OrderService {
                    @Transactional
                    public void save() {}
                }
                """));

        assertThat(violations).isEmpty();
    }

    private SourceFile parse(String code) {
        return new SourceFile(Path.of("Controller.java"), StaticJavaParser.parse(code));
    }
}
