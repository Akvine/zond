package ru.akvine.zond.rules.streams;

import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.StreamChains;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * {@code fields.stream().sorted(byOrder).filter(this::isMapped)}: сортируются и те элементы, которые фильтр
 * следом отбросит. Сортировка - самая дорогая операция цепочки, поэтому отбор ставят перед ней.
 */
@Component
public class FilterAfterSortRule extends AbstractRule {
    private static final String FILTER = "filter";
    private static final String SORTED = "sorted";
    // filter(seen::add), filter(distinctByKey(...)): такой отбор зависит от порядка элементов - после сортировки
    // он оставляет первый по порядку, и переставлять его нельзя
    private static final Pattern ORDER_DEPENDENT = Pattern.compile(
            "(?i)distinct|::add\\b|\\.add\\(|seen|visited|counter|index|AndIncrement|incrementAnd|previous|last");

    @Override
    public String code() {
        return RuleCodes.FILTER_AFTER_SORT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет в стримах filter() сразу после sorted(): сортируются элементы, которые"
                + " фильтр затем отбросит";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr filter : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (FILTER.equals(filter.getNameAsString()) && filter.getArguments().size() == 1
                    && isRightAfterSort(filter) && !TestClasses.isInside(filter)
                    && !ORDER_DEPENDENT.matcher(filter.getArgument(0).toString()).find()) {
                violations.add(violation(sourceFile, filter.getName(),
                        "'filter(...)' стоит после 'sorted(...)': сортируются и те элементы, которые фильтр затем"
                                + " отбросит; поставьте отбор перед сортировкой"));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.STREAM;
    }

    // Между sorted и filter ничего нет: через limit, map или skip отбор перед сортировкой уже не перенести.
    // Из нескольких фильтров подряд находкой считается первый
    private boolean isRightAfterSort(MethodCallExpr filter) {
        List<MethodCallExpr> before = StreamChains.callsBefore(filter);
        return !before.isEmpty() && SORTED.equals(before.get(0).getNameAsString());
    }
}
