package ru.akvine.zond.rules.streams;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.StreamChains;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * names.stream().filter(...).collect(Collectors.toList()).size(): список собирают, чтобы тут же спросить у него
 * одно значение и выбросить. Стрим отвечает на тот же вопрос сам - без списка, а anyMatch и findFirst еще и
 * останавливаются на первом подходящем элементе.
 */
@Component
public class IntermediateCollectionRule extends AbstractRule {
    private static final String COLLECT = "collect";
    private static final String TO_LIST = "toList";
    private static final String GET = "get";
    private static final String FIRST = "0";
    private static final Pattern LIST_COLLECTOR = Pattern.compile("^(Collectors\\.)?to(Unmodifiable)?List\\(\\)$");
    private static final Set<String> STREAM_SOURCES = Set.of("stream", "parallelStream", "of", "lines", "chars");
    // Что спросили у списка -> чем это заменить в самом стриме
    private static final Map<String, String> REPLACEMENTS = Map.of(
            "size", "count()",
            "isEmpty", "findAny().isEmpty() или noneMatch(...)",
            "contains", "anyMatch(...)",
            "stream", "продолжение того же стрима",
            "get", "findFirst()",
            "forEach", "forEach(...) у стрима");

    @Override
    public String code() {
        return RuleCodes.INTERMEDIATE_COLLECTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет список, собранный из стрима ради одного вызова: size, isEmpty, contains, get(0)";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr question : sourceFile.unit().findAll(MethodCallExpr.class)) {
            String replacement = REPLACEMENTS.get(question.getNameAsString());
            Expression scope = question.getScope().map(Nodes::unwrap).orElse(null);
            if (replacement == null || scope == null || !scope.isMethodCallExpr() || !isSuitable(question)
                    || !collectsToList(scope.asMethodCallExpr()) || TestClasses.isInside(question)) {
                continue;
            }
            violations.add(violation(sourceFile, question.getName(),
                    "Список собирают из стрима ради одного вызова " + question.getNameAsString() + "(...): память"
                            + " под все элементы выделяется зря; замените на " + replacement));
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

    // get(0) - первый элемент; get(5) стрим так просто не заменит
    private boolean isSuitable(MethodCallExpr question) {
        return !GET.equals(question.getNameAsString())
                || question.getArguments().size() == 1 && FIRST.equals(question.getArgument(0).toString());
    }

    // ...stream()...collect(Collectors.toList()) либо ...stream()...toList()
    private boolean collectsToList(MethodCallExpr call) {
        boolean toList = TO_LIST.equals(call.getNameAsString()) && call.getArguments().isEmpty()
                || COLLECT.equals(call.getNameAsString()) && call.getArguments().size() == 1
                && LIST_COLLECTOR.matcher(call.getArgument(0).toString()).matches();
        return toList && StreamChains.callsBefore(call).stream()
                .anyMatch(step -> STREAM_SOURCES.contains(step.getNameAsString()));
    }
}
