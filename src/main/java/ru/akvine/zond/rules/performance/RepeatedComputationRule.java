package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.Statement;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * key.toLowerCase().contains(a) || key.toLowerCase().contains(b): одно и то же значение вычисляется в одном
 * выражении дважды. Для строки это лишняя копия, для getCanonicalPath() - обращение к диску, а для запроса
 * к репозиторию - второй запрос к базе.
 */
@Component
public class RepeatedComputationRule extends AbstractRule {
    // Каждый вызов строит новую строку или массив
    private static final Set<String> TEXT_METHODS = Set.of(
            "toLowerCase", "toUpperCase", "trim", "strip", "split", "replaceAll", "replaceFirst", "replace",
            "toCharArray", "getBytes", "format", "pow");
    // Каждый вызов обращается к файловой системе
    private static final Set<String> FILE_METHODS = Set.of(
            "getCanonicalPath", "getCanonicalFile", "toRealPath");
    // it.next().trim(), reader.readLine().trim(): значение от вызова к вызову меняется
    private static final Pattern CHANGING = Pattern.compile(
            "\\b(next\\w*|read\\w*|poll\\w*|pop|take|remove\\w*|random\\w*|incrementAndGet|getAndIncrement)\\(|\\+\\+|--");

    @Override
    public String code() {
        return RuleCodes.REPEATED_COMPUTATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет одно и то же вычисление, повторенное в одном выражении: преобразование строки,"
                + " обращение к диску или запрос к репозиторию";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // Оператор -> текст вызова -> сами вызовы
        Map<Node, Map<String, List<MethodCallExpr>>> byStatement = new IdentityHashMap<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (level(call) == null || TestClasses.isInside(call) || CHANGING.matcher(call.toString()).find()) {
                continue;
            }
            call.findAncestor(Statement.class).ifPresent(statement -> byStatement
                    .computeIfAbsent(statement, key -> new LinkedHashMap<>())
                    .computeIfAbsent(call.toString(), key -> new ArrayList<>())
                    .add(call));
        }
        List<Violation> violations = new ArrayList<>();
        for (Map<String, List<MethodCallExpr>> calls : byStatement.values()) {
            for (List<MethodCallExpr> same : calls.values()) {
                if (same.size() > 1 && !isEitherOr(same.get(0), same.get(1))) {
                    MethodCallExpr repeated = same.get(1);
                    violations.add(violation(sourceFile, repeated,
                            "'" + Nodes.text(repeated) + "' вычисляется в одном выражении " + same.size() + " раза:"
                                    + " каждый вызов делает всю работу заново; вычислите значение один раз"
                                    + " и сохраните в переменную").withLevel(level(repeated)));
                }
            }
        }
        violations.sort((left, right) -> Integer.compare(left.line(), right.line()));
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

    // null - вызов дешевый, повторять его не жалко
    private ErrorLevel level(MethodCallExpr call) {
        if (call.getScope().isEmpty()) {
            return null;
        }
        if (FILE_METHODS.contains(call.getNameAsString()) || Repositories.isRepositoryCall(call)) {
            return ErrorLevel.MINOR;
        }
        return TEXT_METHODS.contains(call.getNameAsString()) ? ErrorLevel.INFO : null;
    }

    // flag ? name.trim() : name.trim().toLowerCase(): выполняется только одна из веток
    private boolean isEitherOr(MethodCallExpr first, MethodCallExpr second) {
        Node current = first.getParentNode().orElse(null);
        while (current != null && !(current instanceof Statement)) {
            if (current instanceof ConditionalExpr choice
                    && inside(choice.getThenExpr(), first) != inside(choice.getThenExpr(), second)
                    && inside(choice.getElseExpr(), first) != inside(choice.getElseExpr(), second)) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    private boolean inside(Node branch, Node node) {
        return branch == node || Nodes.isInside(node, branch);
    }
}
