package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.List;

@Component
public class SleepInTestRule extends AbstractRule {
    private static final String SLEEP = "sleep";

    @Override
    public String code() {
        return RuleCodes.SLEEP_IN_TEST_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет Thread.sleep() в тестах";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // Thread.sleep(...), TimeUnit.SECONDS.sleep(...)
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> SLEEP.equals(call.getNameAsString()) && call.getScope().isPresent())
                .filter(TestClasses::isInside)
                .map(call -> violation(sourceFile, call,
                        "'" + call + "' в тесте: на медленной машине паузы не хватит и тест упадет, на быстрой -"
                                + " время тратится впустую; ждите нужного состояния явно, например через"
                                + " Awaitility"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    // Правило проверяет сами тесты
    @Override
    public boolean appliesToTests() {
        return true;
    }
}
