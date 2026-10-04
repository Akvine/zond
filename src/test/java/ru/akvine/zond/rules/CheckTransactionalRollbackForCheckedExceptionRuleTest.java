package ru.akvine.zond.rules;

import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.logical.CheckTransactionalRollbackForCheckedExceptionRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckTransactionalRollbackForCheckedExceptionRuleTest {
    private final CheckTransactionalRollbackForCheckedExceptionRule rule =
            new CheckTransactionalRollbackForCheckedExceptionRule();

    @Test
    void findsMethodsWithoutRollbackRule() {
        List<Violation> violations = rule.check(parse("""
                class Service {
                    @Transactional
                    public void plain() throws IOException {}

                    @Transactional(readOnly = true, noRollbackFor = IllegalStateException.class)
                    void otherAttributes() throws java.sql.SQLException, IllegalStateException, CustomException {}

                    @Transactional(Transactional.TxType.REQUIRED)
                    protected void jakarta() throws Exception {}
                }
                """));

        assertThat(violations).extracting(Violation::line).containsExactly(2, 5, 8);
        assertThat(violations).allMatch(violation -> violation.ruleCode().equals("jr:3"));
        assertThat(violations).allMatch(violation -> violation.errorLevel() == ErrorLevel.CRITICAL);
        assertThat(violations.get(0).message()).contains("'plain'", "IOException");
        assertThat(violations.get(1).message())
                .contains("SQLException, CustomException")
                .doesNotContain("IllegalStateException");
    }

    @Test
    void usesClassLevelAnnotationForPublicMethods() {
        List<Violation> violations = rule.check(parse("""
                @Transactional(rollbackFor = Exception.class)
                class Service {
                    public void coveredByClass() throws IOException {}

                    @Transactional
                    public void overridesClass() throws IOException {}
                }

                @Transactional
                class Other {
                    public void publicMethod() throws IOException {}

                    void notPublic() throws IOException {}
                }
                """));

        assertThat(violations).extracting(Violation::line).containsExactly(5, 11);
    }

    @Test
    void ignoresValidCode() {
        List<Violation> violations = rule.check(parse("""
                class Service {
                    @Transactional(rollbackFor = Exception.class)
                    public void rollbackFor() throws IOException {}

                    @Transactional(rollbackForClassName = "java.io.IOException")
                    public void rollbackForClassName() throws IOException {}

                    @Transactional(rollbackOn = IOException.class)
                    public void rollbackOn() throws IOException {}

                    @Transactional
                    public void noThrows() {}

                    @Transactional
                    public void onlyUnchecked() throws IllegalArgumentException, RuntimeException {}

                    @Transactional
                    public <E extends RuntimeException> void typeParameter() throws E {}

                    @Transactional
                    private void privateMethod() throws IOException {}

                    public void notTransactional() throws IOException {}
                }
                """));

        assertThat(violations).isEmpty();
    }

    private SourceFile parse(String code) {
        return new SourceFile(Path.of("Service.java"), StaticJavaParser.parse(code));
    }
}
