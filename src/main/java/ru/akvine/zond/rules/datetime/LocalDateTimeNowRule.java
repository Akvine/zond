package ru.akvine.zond.rules.datetime;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.Rule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class LocalDateTimeNowRule implements Rule {
    private static final String LOCAL_DATE_TIME = "LocalDateTime";
    private static final String NOW = "now";
    // Текст, местная дата и время, явное указание пояса: так значение не сравнивают с метками других серверов
    private static final Set<String> LOCAL_READS = Set.of(
            "format", "toString", "toLocalDate", "toLocalTime", "atZone", "atOffset", "toInstant", "toEpochSecond");
    private static final String GETTER_PREFIX = "get";
    private static final Pattern ADJUSTMENT = Pattern.compile("(plus|minus|with)\\w*|truncatedTo");
    // formatter.format(now), DateUtils.formatLocalDateTime(now), log.info("...", now), builder.append(now)
    private static final Pattern TEXT_CONSUMER = Pattern.compile(
            "format\\w*|print\\w*|append|valueOf|trace|debug|info|warn|error");
    private static final int MAX_VARIABLE_HOPS = 1;

    @Override
    public String name() {
        return getClass().getSimpleName();
    }

    @Override
    public String code() {
        return RuleCodes.LOCAL_DATE_TIME_NOW_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет LocalDateTime.now() для временных меток вместо типов с часовым поясом";
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // now(zone) и now(clock) не трогаем: там пояс выбран явно
        List<MethodCallExpr> calls = sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> MethodCalls.isCallOn(call, LOCAL_DATE_TIME, NOW))
                .filter(call -> call.getArguments().isEmpty())
                .toList();
        if (calls.isEmpty()) {
            return List.of();
        }
        // Как хранить время, решают один раз на проект: десяток одинаковых находок в файле ничего не добавляет
        // к одной. Показываем одно место и число остальных. Время, которое сохраняют, сравнивают или отдают дальше,
        // важнее времени, которое тут же превращают в текст: показываем его, если оно в файле есть
        Optional<MethodCallExpr> kept = calls.stream().filter(call -> !isLocalUse(call, MAX_VARIABLE_HOPS)).findFirst();
        MethodCallExpr shown = kept.orElse(calls.get(0));
        String others = calls.size() > 1 ? " (в этом файле еще " + (calls.size() - 1) + " таких мест)" : "";
        Violation violation = new Violation(
                errorLevel(),
                errorType(),
                code(),
                name(),
                sourceFile.path(),
                shown.getBegin().map(position -> position.line).orElse(0),
                "LocalDateTime.now() для временной метки: значение берется в часовом поясе сервера"
                        + " и не хранит его, один и тот же момент на разных серверах даст разное время;"
                        + " используйте Instant.now(), ZonedDateTime.now() или OffsetDateTime.now()" + others
                        + (kept.isPresent() ? "" : ". В этом файле значение идет только в текст и в местные поля"
                        + " даты (format, toLocalDate, getHour), поэтому это подозрение"));
        return List.of(kept.isPresent() ? violation : violation.withConfidence(Confidence.SUSPICION));
    }

    /**
     * Значение никуда не сохраняют и ни с чем не сравнивают: из него тут же делают текст (format, склейка строк,
     * сообщение лога), берут местную дату либо поля (toLocalDate, getHour) или сами приписывают пояс (atZone).
     * LocalDateTime now = LocalDateTime.now(): смотрим, что делают с переменной
     */
    private boolean isLocalUse(Expression value, int hops) {
        Expression current = value;
        Node parent = parentOf(current);
        // LocalDateTime.now().minusDays(1).truncatedTo(DAYS).format(...)
        while (parent instanceof MethodCallExpr next && isScopeOf(next, current)) {
            String method = next.getNameAsString();
            if (LOCAL_READS.contains(method) || method.startsWith(GETTER_PREFIX)) {
                return true;
            }
            if (!ADJUSTMENT.matcher(method).matches()) {
                return false;
            }
            current = next;
            parent = parentOf(current);
        }
        if (parent instanceof VariableDeclarator variable && hops > 0
                && variable.getParentNode().filter(VariableDeclarationExpr.class::isInstance).isPresent()) {
            String name = variable.getNameAsString();
            return Nodes.enclosingCallable(variable)
                    .map(callable -> callable.findAll(NameExpr.class).stream()
                            .filter(usage -> usage.getNameAsString().equals(name))
                            .allMatch(usage -> isLocalUse(usage, hops - 1)))
                    .orElse(false);
        }
        Expression argument = current;
        if (parent instanceof BinaryExpr concatenation) {
            return concatenation.getOperator() == BinaryExpr.Operator.PLUS
                    && concatenation.findFirst(StringLiteralExpr.class).isPresent();
        }
        return parent instanceof MethodCallExpr consumer && TEXT_CONSUMER.matcher(consumer.getNameAsString()).matches()
                && consumer.getArguments().stream().anyMatch(passed -> Nodes.unwrap(passed) == argument);
    }

    private boolean isScopeOf(MethodCallExpr call, Expression value) {
        return call.getScope().map(Nodes::unwrap).filter(scope -> scope == value).isPresent();
    }

    private Node parentOf(Node node) {
        Node parent = node.getParentNode().orElse(null);
        while (parent instanceof EnclosedExpr) {
            parent = parent.getParentNode().orElse(null);
        }
        return parent;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.DATE_AND_TIME;
    }
}
