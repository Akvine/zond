package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.StreamChains;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code repository.findByStatus(status).stream().sorted(byDate).limit(10)}: из базы приходят все строки,
 * сортируются в памяти, и остаются первые десять. ORDER BY и ограничение выборки в самом запросе отдадут
 * только нужные строки. То же с skip(n) и subList(0, n).
 */
@Component
public class InMemoryLimitAfterQueryRule extends AbstractRule {
    private static final Set<String> CUTS = Set.of("limit", "skip");
    private static final String SUB_LIST = "subList";
    private static final String STREAM = "stream";
    private static final String SORTED = "sorted";
    private static final Pattern READ = Pattern.compile("(find|get|read|query|search|load|fetch|select)\\w*");
    // findAll(PageRequest.of(0, 100)), findByStatus(status, pageable): выборка уже ограничена
    private static final Pattern PAGED = Pattern.compile("(?i)page|limit|slice|top\\d|first\\d");

    @Override
    public String code() {
        return RuleCodes.IN_MEMORY_LIMIT_AFTER_QUERY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет limit(), skip() и subList() сразу после запроса к репозиторию: выборка"
                + " загружается целиком, а лишнее отбрасывается в памяти";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr cut : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (TestClasses.isInside(cut)) {
                continue;
            }
            Optional<MethodCallExpr> query = CUTS.contains(cut.getNameAsString()) && cut.getArguments().size() == 1
                    ? findStreamedQuery(cut)
                    : findListQuery(cut);
            query.filter(this::isUnlimitedSelection).ifPresent(found -> violations.add(violation(
                    sourceFile, cut.getName(),
                    "'" + cut.getNameAsString() + "(...)' выполняется в памяти после '" + found.getNameAsString()
                            + "(...)': из базы приходят все строки" + (isSorted(cut) ? ", сортируются в памяти" : "")
                            + ", и лишние отбрасываются; ограничьте выборку в самом запросе: Pageable или Limit,"
                            + " findFirst10By..., ORDER BY")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    // query.stream()[.sorted(...)].limit(n): между запросом и отсечением нет отбора, который в запрос не перенести
    private Optional<MethodCallExpr> findStreamedQuery(MethodCallExpr cut) {
        List<MethodCallExpr> before = StreamChains.callsBefore(cut);
        int stream = 0;
        while (stream < before.size() && SORTED.equals(before.get(stream).getNameAsString())) {
            stream++;
        }
        if (stream >= before.size() || !STREAM.equals(before.get(stream).getNameAsString())) {
            return Optional.empty();
        }
        return stream + 1 < before.size()
                ? Optional.of(before.get(stream + 1))
                : before.get(stream).getScope().flatMap(this::queryBehind);
    }

    // repository.findAll().subList(0, 10)
    private Optional<MethodCallExpr> findListQuery(MethodCallExpr cut) {
        if (!SUB_LIST.equals(cut.getNameAsString()) || cut.getArguments().size() != 2) {
            return Optional.empty();
        }
        return cut.getScope().flatMap(this::queryBehind);
    }

    // Сам запрос либо переменная, в которую его положили
    private Optional<MethodCallExpr> queryBehind(Expression scope) {
        Expression value = Nodes.unwrap(scope);
        if (value.isNameExpr()) {
            value = LocalTypes.findInitializer(value).map(Nodes::unwrap).orElse(value);
        }
        return Optional.of(value).filter(Expression::isMethodCallExpr).map(Expression::asMethodCallExpr);
    }

    private boolean isUnlimitedSelection(MethodCallExpr query) {
        return Repositories.isRepositoryCall(query) && READ.matcher(query.getNameAsString()).matches()
                && !PAGED.matcher(query.getNameAsString()).find()
                && query.getArguments().stream().noneMatch(argument -> PAGED.matcher(argument.toString()).find());
    }

    private boolean isSorted(MethodCallExpr cut) {
        return StreamChains.callsBefore(cut).stream().anyMatch(call -> SORTED.equals(call.getNameAsString()));
    }
}
