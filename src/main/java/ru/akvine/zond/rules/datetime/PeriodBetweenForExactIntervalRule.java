package ru.akvine.zond.rules.datetime;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.Rule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.MethodCalls;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class PeriodBetweenForExactIntervalRule implements Rule {
    private static final String PERIOD = "Period";
    private static final String BETWEEN = "between";

    private static final String GET_DAYS = "getDays";
    private static final String GET_MONTHS = "getMonths";
    private static final String GET_YEARS = "getYears";
    private static final String TO_TOTAL_MONTHS = "toTotalMonths";

    @Override
    public String name() {
        return getClass().getSimpleName();
    }

    @Override
    public String code() {
        return RuleCodes.PERIOD_BETWEEN_FOR_EXACT_INTERVAL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет Period.between(), из которого берется одна составляющая как точный интервал";
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr between : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!MethodCalls.isCallOn(between, PERIOD, BETWEEN)) {
                continue;
            }

            List<MethodCallExpr> usages = findUsages(between);
            Set<String> used = usages.stream().map(MethodCallExpr::getNameAsString).collect(Collectors.toSet());

            // Период - это "годы + месяцы + дни". Одна составляющая без старших - не длина интервала, а остаток
            boolean daysOnly = !used.contains(GET_MONTHS) && !used.contains(GET_YEARS)
                    && !used.contains(TO_TOTAL_MONTHS);
            boolean monthsWithoutYears = !used.contains(GET_YEARS) && !used.contains(TO_TOTAL_MONTHS);

            for (MethodCallExpr usage : usages) {
                if (GET_DAYS.equals(usage.getNameAsString()) && daysOnly) {
                    violations.add(violation(sourceFile, usage,
                            "Period.between(...).getDays() возвращает только остаток дней сверх полных месяцев,"
                                    + " а не длину интервала в днях; используйте ChronoUnit.DAYS.between(...)"));
                }
                if (GET_MONTHS.equals(usage.getNameAsString()) && monthsWithoutYears) {
                    violations.add(violation(sourceFile, usage,
                            "Period.between(...).getMonths() возвращает только остаток месяцев сверх полных лет,"
                                    + " а не длину интервала в месяцах; используйте ChronoUnit.MONTHS.between(...)"
                                    + " или toTotalMonths()"));
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
        return ErrorType.DATE_AND_TIME;
    }

    /**
     * @return вызовы методов на результате Period.between(...): по цепочке либо через переменную.
     * Пусто, если проследить использование нельзя (период возвращается, передается аргументом и т.п.)
     */
    private List<MethodCallExpr> findUsages(MethodCallExpr between) {
        Node parent = between.getParentNode().orElse(null);

        // Period.between(a, b).getDays()
        if (parent instanceof MethodCallExpr chained && isScopeOf(between, chained)) {
            return List.of(chained);
        }

        // Period period = Period.between(a, b); ... period.getDays()
        if (parent instanceof VariableDeclarator variable) {
            return findVariableUsages(variable);
        }
        return List.of();
    }

    private List<MethodCallExpr> findVariableUsages(VariableDeclarator variable) {
        Node container = findContainer(variable);
        if (container == null) {
            return List.of();
        }

        List<MethodCallExpr> usages = new ArrayList<>();
        for (NameExpr reference : container.findAll(NameExpr.class)) {
            if (!reference.getNameAsString().equals(variable.getNameAsString())) {
                continue;
            }

            Node parent = reference.getParentNode().orElse(null);
            if (parent instanceof MethodCallExpr call && isScopeOf(reference, call)) {
                usages.add(call);
            } else {
                // Период уходит куда-то еще - как его там используют, неизвестно
                return List.of();
            }
        }
        return usages;
    }

    // Локальная переменная видна в своем блоке, поле - во всем классе
    private Node findContainer(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null && !(current instanceof BlockStmt) && !(current instanceof TypeDeclaration<?>)) {
            current = current.getParentNode().orElse(null);
        }
        return current;
    }

    private boolean isScopeOf(Node node, MethodCallExpr call) {
        return call.getScope().filter(scope -> scope == node).isPresent();
    }

    private Violation violation(SourceFile sourceFile, MethodCallExpr call, String message) {
        return new Violation(
                errorLevel(),
                errorType(),
                code(),
                name(),
                sourceFile.path(),
                call.getBegin().map(position -> position.line).orElse(0),
                message);
    }
}
