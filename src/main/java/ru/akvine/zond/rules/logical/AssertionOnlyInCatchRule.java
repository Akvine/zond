package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class AssertionOnlyInCatchRule extends AbstractRule {
    private static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest");
    private static final String ASSERT_PREFIX = "assert";
    // С этими вызовами тест падает и тогда, когда исключения не было
    private static final Set<String> FAILING = Set.of("fail", "assertThrows", "assertThatThrownBy", "catchThrowable",
            "assertThatExceptionOfType", "expectThrows", "shouldHaveThrown", "failBecauseExceptionWasNotThrown");

    @Override
    public String code() {
        return RuleCodes.ASSERTION_ONLY_IN_CATCH_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует тесты и ищет проверки, которые стоят только в catch: без исключения тест проходит, ничего не проверив";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            boolean isTest = TestClasses.annotationNames(method).stream().anyMatch(TEST_ANNOTATIONS::contains);
            boolean failsWithoutException = method.findAll(MethodCallExpr.class).stream()
                    .anyMatch(call -> FAILING.contains(call.getNameAsString()));
            if (!isTest || failsWithoutException) {
                continue;
            }
            for (TryStmt tryStatement : method.findAll(TryStmt.class)) {
                // throw в try означает, что исключение создают сами: тогда проверка в catch выполнится всегда
                if (!tryStatement.getTryBlock().findAll(ThrowStmt.class).isEmpty()) {
                    continue;
                }
                for (CatchClause clause : tryStatement.getCatchClauses()) {
                    if (hasAssertion(clause)) {
                        violations.add(violation(sourceFile, clause,
                                "Проверки стоят в catch, а после вызова в try нет fail(): если исключение не"
                                        + " возникнет, тест пройдет, ничего не проверив; используйте assertThrows"
                                        + " либо добавьте fail() последней строкой try"));
                    }
                }
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private boolean hasAssertion(CatchClause clause) {
        return clause.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> call.getNameAsString().startsWith(ASSERT_PREFIX));
    }
}
