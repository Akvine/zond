package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.TestPaths;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Строки таблицы лежат списком карт "колонка → значение", и на каждой строке от имени колонки заново считается
 * хеш: {@code row.forEach((column, value) -> result.put(hash(column), value))}. Ключи у всех строк одни и те же,
 * поэтому хеш считается строк × колонок раз, хотя разных значений столько, сколько колонок.
 * <p>
 * Правило смотрит только на хеши и шифрование: регулярные выражения и рефлексию в цикле и в методах, вызываемых
 * из цикла, показывают jr:239, jr:419, jr:518 и jr:533.
 */
@Component
public class KeyDerivedValuePerRowRule extends AbstractRule implements ProjectRule {
    private static final int CALL_DEPTH = 2;
    private static final String FOR_EACH = "forEach";
    private static final String ENTRY_SET = "entrySet";
    private static final String KEY_SET = "keySet";
    private static final String GET_KEY = "getKey";
    private static final int MAP_CONSUMER_PARAMETERS = 2;

    // DigestUtils.sha256Hex(...), Hashing.sha256().hashString(...), messageDigest.digest(...), mac.doFinal(...)
    private static final Set<String> DIGEST_TYPES = Set.of(
            "DigestUtils", "HmacUtils", "Hashing", "MessageDigest", "DigestUtil", "SecureUtil", "Md5Crypt", "Mac", "Cipher");
    private static final Set<String> DIGEST_METHODS = Set.of("nameUUIDFromBytes");
    private static final Pattern DIGEST_NAME =
            Pattern.compile("(messageDigest|digest|md5|sha\\d*|hmac|mac|cipher)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> DIGEST_OPERATIONS = Set.of("digest", "doFinal");
    private static final Pattern CONSTANT = Pattern.compile("[A-Z][A-Z0-9_]*");

    /**
     * Обход карты, на котором виден ее ключ
     *
     * @param body     что выполняется для каждой записи
     * @param variable параметр лямбды или переменная цикла
     * @param viaEntry true, если переменная - запись карты и ключ берется через getKey()
     */
    private record KeyIteration(Node node, Node body, String variable, boolean viaEntry) {
    }

    @Override
    public String code() {
        return RuleCodes.KEY_DERIVED_VALUE_PER_ROW_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет хеш или шифрование от ключа карты во вложенном обходе: для каждой строки данных"
                + " значение заново вычисляется от одних и тех же ключей";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        CallGraph graph = CallGraph.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            if (TestPaths.isTest(sourceFile.path())) {
                continue;
            }
            Set<Node> reported = Collections.newSetFromMap(new IdentityHashMap<>());
            List<Violation> found = new ArrayList<>();
            for (KeyIteration iteration : findIterations(sourceFile)) {
                // Сам обход карты повторяется для каждой строки данных
                if (!CodeContexts.isRepeatedOverData(iteration.node()) || CodeContexts.isStartup(iteration.node())) {
                    continue;
                }
                for (MethodCallExpr call : iteration.body().findAll(MethodCallExpr.class)) {
                    // Вложенный вызов уже показан вместе с внешним
                    if (call.findAncestor(MethodCallExpr.class).filter(reported::contains).isPresent()
                            || !dependsOnlyOnKey(call, iteration)) {
                        continue;
                    }
                    findCost(call, graph, CALL_DEPTH).ifPresent(cost -> {
                        reported.add(call);
                        found.add(violation(sourceFile, call,
                                "'" + Nodes.text(call) + "' вычисляется от ключа карты заново на каждой строке"
                                        + " данных (" + cost + "): ключи у строк одни и те же, а значение считается"
                                        + " строк × ключей раз; посчитайте его один раз на ключ - карта"
                                        + " \"ключ → значение\" перед циклом либо computeIfAbsent"));
                    });
                }
            }
            // Циклы и лямбды собираются по отдельности - находки файла выстраиваем по строкам
            found.sort(Comparator.comparingInt(Violation::line));
            violations.addAll(found);
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

    private List<KeyIteration> findIterations(SourceFile sourceFile) {
        List<KeyIteration> iterations = new ArrayList<>();
        // for (Map.Entry<String, Object> entry : row.entrySet()), for (String column : row.keySet())
        for (ForEachStmt loop : sourceFile.unit().findAll(ForEachStmt.class)) {
            Expression source = Nodes.unwrap(loop.getIterable());
            if (source.isMethodCallExpr() && loop.getVariable().getVariables().size() == 1) {
                String method = source.asMethodCallExpr().getNameAsString();
                String variable = loop.getVariable().getVariable(0).getNameAsString();
                if (ENTRY_SET.equals(method) || KEY_SET.equals(method)) {
                    iterations.add(new KeyIteration(loop, loop.getBody(), variable, ENTRY_SET.equals(method)));
                }
            }
        }
        for (LambdaExpr lambda : sourceFile.unit().findAll(LambdaExpr.class)) {
            Optional<MethodCallExpr> operation = lambda.getParentNode()
                    .filter(MethodCallExpr.class::isInstance)
                    .map(MethodCallExpr.class::cast);
            if (operation.isEmpty()) {
                continue;
            }
            // row.forEach((column, value) -> ...): два параметра бывают только у Map.forEach
            if (FOR_EACH.equals(operation.get().getNameAsString())
                    && lambda.getParameters().size() == MAP_CONSUMER_PARAMETERS) {
                iterations.add(new KeyIteration(operation.get(), lambda.getBody(),
                        lambda.getParameter(0).getNameAsString(), false));
                continue;
            }
            // row.entrySet().stream().map(entry -> ...), row.keySet().forEach(column -> ...)
            if (lambda.getParameters().size() == 1) {
                findSource(operation.get()).ifPresent(method -> iterations.add(new KeyIteration(
                        operation.get(), lambda.getBody(), lambda.getParameter(0).getNameAsString(),
                        ENTRY_SET.equals(method))));
            }
        }
        return iterations;
    }

    // Первое звено цепочки: entrySet() или keySet()
    private Optional<String> findSource(MethodCallExpr operation) {
        Optional<Expression> scope = operation.getScope();
        while (scope.isPresent() && Nodes.unwrap(scope.get()).isMethodCallExpr()) {
            MethodCallExpr link = Nodes.unwrap(scope.get()).asMethodCallExpr();
            if ((ENTRY_SET.equals(link.getNameAsString()) || KEY_SET.equals(link.getNameAsString()))
                    && link.getArguments().isEmpty()) {
                return Optional.of(link.getNameAsString());
            }
            scope = link.getScope();
        }
        return Optional.empty();
    }

    /**
     * @return true, если результат вызова определяется одним ключом карты: в аргументах только ключ и константы,
     * а метод вызван у класса, у поля либо у самого ключа
     */
    private boolean dependsOnlyOnKey(MethodCallExpr call, KeyIteration iteration) {
        boolean usesKey = false;
        for (NameExpr name : call.findAll(NameExpr.class)) {
            String identifier = name.getNameAsString();
            if (identifier.equals(iteration.variable())) {
                // entry.getValue() - это уже значение, а не ключ
                if (iteration.viaEntry() && !isKeyAccess(name)) {
                    return false;
                }
                usesKey = true;
                continue;
            }
            boolean isConstantOrType = CONSTANT.matcher(identifier).matches()
                    || Character.isUpperCase(identifier.charAt(0));
            boolean isField = !LocalTypes.isLocal(name, identifier)
                    && LocalTypes.findField(name, identifier).isPresent();
            if (!isConstantOrType && !isField) {
                return false;
            }
        }
        return usesKey;
    }

    private boolean isKeyAccess(NameExpr entry) {
        return entry.getParentNode()
                .filter(MethodCallExpr.class::isInstance)
                .map(MethodCallExpr.class::cast)
                .filter(access -> GET_KEY.equals(access.getNameAsString())
                        && access.getScope().filter(scope -> scope == entry).isPresent())
                .isPresent();
    }

    /**
     * @return что в вызове дорого: сам хеш либо метод проекта, который его считает
     */
    private Optional<String> findCost(MethodCallExpr call, CallGraph graph, int depth) {
        Optional<String> own = describeDigest(call);
        if (own.isPresent() || depth == 0) {
            return own;
        }
        List<MethodDeclaration> targets = graph.targetsOf(call);
        if (targets.size() != 1 || targets.get(0).getBody().isEmpty()) {
            return Optional.empty();
        }
        MethodDeclaration target = targets.get(0);
        for (MethodCallExpr inner : target.findAll(MethodCallExpr.class)) {
            Optional<String> cost = findCost(inner, graph, depth - 1);
            if (cost.isPresent()) {
                return Optional.of(target.getNameAsString() + " → " + cost.get());
            }
        }
        return Optional.empty();
    }

    private Optional<String> describeDigest(MethodCallExpr call) {
        String method = call.getNameAsString();
        if (call.getScope().isEmpty()) {
            return Optional.empty();
        }
        Expression scope = Nodes.unwrap(call.getScope().get());
        String receiver = MethodCalls.receiverName(scope);
        boolean onDigestType = scope.isNameExpr() && DIGEST_TYPES.contains(receiver) && !"getInstance".equals(method);
        boolean isDigestOperation = DIGEST_OPERATIONS.contains(method)
                && LocalTypes.isAnyOf(scope, DIGEST_TYPES, () -> DIGEST_NAME.matcher(receiver).matches());
        return onDigestType || isDigestOperation || DIGEST_METHODS.contains(method)
                ? Optional.of(receiver + "." + method)
                : Optional.empty();
    }
}
