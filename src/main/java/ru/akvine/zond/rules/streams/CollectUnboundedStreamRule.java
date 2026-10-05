package ru.akvine.zond.rules.streams;

import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.StreamChains;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CollectUnboundedStreamRule extends AbstractRule {
    private static final String COLLECT = "collect";
    private static final String FILES = "Files";
    private static final String LINES = "lines";
    private static final String FIND_ALL = "findAll";

    // Завершающие операции, которые складывают в память весь стрим целиком
    private static final Set<String> MATERIALIZING_OPERATIONS = Set.of("toList", "toArray");
    private static final Set<String> MATERIALIZING_COLLECTORS = Set.of(
            "toList", "toSet", "toCollection", "toUnmodifiableList", "toUnmodifiableSet");

    private static final Set<String> FILES_STREAM_METHODS = Set.of("lines", "walk", "find");
    private static final Set<String> PARALLEL_METHODS = Set.of("parallelStream", "parallel");

    @Override
    public String code() {
        return RuleCodes.COLLECT_UNBOUNDED_STREAM_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет сбор в коллекцию стримов неограниченного размера: файлов, бесконечных"
                + " генераторов, findAll() и параллельных стримов";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr terminal : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!isMaterializing(terminal)) {
                continue;
            }

            List<MethodCallExpr> chain = StreamChains.callsBefore(terminal);
            if (chain.stream().anyMatch(StreamChains::isLimiting)) {
                continue;
            }

            chain.stream()
                    .map(this::describeSource)
                    .flatMap(Optional::stream)
                    .findFirst()
                    .ifPresent(source -> violations.add(violation(sourceFile, terminal,
                            "Весь стрим из " + source + " собирается в память через " + terminal.getNameAsString()
                                    + "(...): на большом объеме будет OutOfMemoryError; ограничьте стрим"
                                    + " через limit() либо обрабатывайте элементы по одному")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.STREAM;
    }

    // stream.toList(), stream.toArray(), stream.collect(Collectors.toList())
    private boolean isMaterializing(MethodCallExpr call) {
        if (call.getScope().isEmpty()) {
            return false;
        }
        if (MATERIALIZING_OPERATIONS.contains(call.getNameAsString())) {
            return true;
        }
        return COLLECT.equals(call.getNameAsString())
                && call.getArguments().size() == 1
                && call.getArgument(0).isMethodCallExpr()
                && MATERIALIZING_COLLECTORS.contains(call.getArgument(0).asMethodCallExpr().getNameAsString());
    }

    /**
     * @return описание источника, размер которого заранее неизвестен или не ограничен
     */
    private Optional<String> describeSource(MethodCallExpr call) {
        String name = call.getNameAsString();
        if (StreamChains.isInfiniteSource(call)) {
            return Optional.of("бесконечного источника " + call.getScope().get() + "." + name + "(...)");
        }
        if (FILES_STREAM_METHODS.contains(name) && MethodCalls.isCallOn(call, FILES, name)) {
            return Optional.of("Files." + name + "(...)");
        }
        // reader.lines()
        if (LINES.equals(name) && call.getArguments().isEmpty() && call.getScope().isPresent()) {
            return Optional.of(call.getScope().get() + ".lines()");
        }
        // repository.findAll().stream(): вся таблица целиком
        if (FIND_ALL.equals(name) && call.getArguments().isEmpty() && call.getScope().isPresent()) {
            return Optional.of(call.getScope().get() + ".findAll()");
        }
        if (PARALLEL_METHODS.contains(name) && call.getArguments().isEmpty()) {
            return Optional.of("параллельного стрима " + name + "()");
        }
        return Optional.empty();
    }
}
