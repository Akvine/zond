package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ConfigProperty;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.ConfigKeys;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TrustAllSslRule extends AbstractContextRule {
    // Методы X509TrustManager: пустое тело означает "доверять любому сертификату"
    private static final Set<String> TRUST_CHECKS = Set.of("checkServerTrusted", "checkClientTrusted");

    // Готовые "доверять всем" из библиотек
    private static final Set<String> TRUST_ALL_NAMES = Set.of(
            "NoopHostnameVerifier", "TrustAllStrategy", "TrustSelfSignedStrategy", "InsecureTrustManagerFactory",
            "ALLOW_ALL_HOSTNAME_VERIFIER");

    // Методы, которым проверку передают лямбдой: (host, session) -> true, (chain, authType) -> true
    private static final Set<String> VERIFIER_SETTERS = Set.of(
            "setHostnameVerifier", "setDefaultHostnameVerifier", "hostnameVerifier", "setSSLHostnameVerifier",
            "loadTrustMaterial");

    private static final String VALUE = "Value";
    // @Value("${http.client.verify-hostname:true}")
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^:}]+)(?::([^}]*))?}");
    private static final Pattern GETTER = Pattern.compile("^(?:is|get)([A-Z].*)$");
    private static final String EQUALS = "equals";
    private static final String BOOLEAN_TRUE = "Boolean.TRUE";
    private static final String BOOLEAN_FALSE = "Boolean.FALSE";
    private static final String TRUE = "true";
    private static final String FALSE = "false";

    /**
     * Место, где проверка отключается
     *
     * @param source как отключение записано в коде - для текста находки
     */
    private record Disabling(Node node, String source) {
    }

    /**
     * Флаг, от которого зависит, выполнится ли отключение
     *
     * @param disablesWhen значение флага, при котором проверка отключается
     */
    private record Flag(String name, boolean disablesWhen) {
    }

    @Override
    public String code() {
        return RuleCodes.TRUST_ALL_SSL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и настройки и ищет отключенную проверку SSL-сертификатов и имени хоста";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        return context.sources().stream()
                .flatMap(sourceFile -> check(sourceFile, context.configFiles()).stream())
                .toList();
    }

    // Один файл без настроек: отключение под условием тогда судится только по значению флага в коде
    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return check(sourceFile, List.of());
    }

    // Отключение проверки записано в коде прямо, а ветка с ним выполняется по значению настройки
    @Override
    public Confidence confidence() {
        return Confidence.CONFIRMED;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    private List<Violation> check(SourceFile sourceFile, List<ConfigFile> configFiles) {
        List<Violation> violations = new ArrayList<>();
        for (Disabling disabling : findDisablings(sourceFile)) {
            Optional<Node> branch = enclosingBranch(disabling.node());
            if (branch.isEmpty()) {
                violations.add(report(sourceFile, disabling, ""));
                continue;
            }
            // Отключение стоит под условием: это переключатель. Ошибка это или нет, решает значение флага -
            // в настройках рабочей среды либо по умолчанию в коде
            flagOf(branch.get(), disabling.node())
                    .flatMap(flag -> describeEnabled(flag, disabling.node(), configFiles))
                    .ifPresent(reason -> violations.add(report(sourceFile, disabling, reason)));
        }
        violations.sort(Comparator.comparingInt(Violation::line));
        return violations;
    }

    private List<Disabling> findDisablings(SourceFile sourceFile) {
        List<Disabling> found = new ArrayList<>();
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            boolean isEmptyCheck = TRUST_CHECKS.contains(method.getNameAsString())
                    && method.getBody().filter(body -> body.getStatements().isEmpty()).isPresent();
            if (isEmptyCheck) {
                found.add(new Disabling(method, "пустой " + method.getNameAsString() + "(...)"));
            }
        }
        for (SimpleName name : sourceFile.unit().findAll(SimpleName.class)) {
            if (TRUST_ALL_NAMES.contains(name.getIdentifier())) {
                found.add(new Disabling(name, name.getIdentifier()));
            }
        }
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (VERIFIER_SETTERS.contains(call.getNameAsString())
                    && call.getArguments().stream().anyMatch(argument ->
                    argument.isLambdaExpr() && alwaysTrue(argument.asLambdaExpr()))) {
                found.add(new Disabling(call, call.getNameAsString() + "(... -> true)"));
            }
        }
        return found;
    }

    /**
     * @return ближайший if либо тернарный оператор, в ветке (не в условии) которого стоит узел
     */
    private Optional<Node> enclosingBranch(Node node) {
        Node child = node;
        Node parent = node.getParentNode().orElse(null);
        while (parent != null && !(parent instanceof CallableDeclaration) && !(parent instanceof TypeDeclaration)) {
            boolean isBranch = parent instanceof IfStmt statement && statement.getCondition() != child
                    || parent instanceof ConditionalExpr conditional && conditional.getCondition() != child;
            if (isBranch) {
                return Optional.of(parent);
            }
            child = parent;
            parent = parent.getParentNode().orElse(null);
        }
        return Optional.empty();
    }

    /**
     * @return флаг из условия и его значение, при котором выполняется ветка с узлом; пусто, если условие
     * сложнее одного флага - тогда о нем судить нельзя
     */
    private Optional<Flag> flagOf(Node branch, Node node) {
        Expression condition;
        boolean inThen;
        if (branch instanceof IfStmt statement) {
            condition = statement.getCondition();
            inThen = statement.getThenStmt().isAncestorOf(node) || statement.getThenStmt() == node;
        } else {
            ConditionalExpr conditional = (ConditionalExpr) branch;
            condition = conditional.getCondition();
            inThen = conditional.getThenExpr().isAncestorOf(node) || conditional.getThenExpr() == node;
        }

        boolean negated = false;
        Expression flag = Nodes.unwrap(condition);
        while (flag instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
            negated = !negated;
            flag = Nodes.unwrap(unary.getExpression());
        }
        // Boolean.TRUE.equals(flag), Boolean.FALSE.equals(flag)
        if (flag instanceof MethodCallExpr call && EQUALS.equals(call.getNameAsString())
                && call.getArguments().size() == 1 && call.getScope().isPresent()) {
            String constant = call.getScope().get().toString();
            if (BOOLEAN_TRUE.equals(constant) || BOOLEAN_FALSE.equals(constant)) {
                negated ^= BOOLEAN_FALSE.equals(constant);
                flag = Nodes.unwrap(call.getArgument(0));
            }
        }
        boolean disablesWhen = inThen != negated;
        return nameOf(flag).map(name -> new Flag(name, disablesWhen));
    }

    private Optional<String> nameOf(Expression flag) {
        if (flag.isNameExpr()) {
            return Optional.of(flag.asNameExpr().getNameAsString());
        }
        if (flag.isFieldAccessExpr()) {
            return Optional.of(flag.asFieldAccessExpr().getNameAsString());
        }
        if (flag.isMethodCallExpr() && flag.asMethodCallExpr().getArguments().isEmpty()) {
            Matcher getter = GETTER.matcher(flag.asMethodCallExpr().getNameAsString());
            if (getter.matches()) {
                return Optional.of(Character.toLowerCase(getter.group(1).charAt(0)) + getter.group(1).substring(1));
            }
        }
        return Optional.empty();
    }

    /**
     * @return пояснение, почему ветка с отключением выполняется; пусто, если флаг ее не включает
     * либо его значение неизвестно
     */
    private Optional<String> describeEnabled(Flag flag, Node node, List<ConfigFile> configFiles) {
        Optional<VariableDeclarator> field = node.findAncestor(TypeDeclaration.class)
                .flatMap(type -> ((TypeDeclaration<?>) type).getFields().stream()
                        .flatMap(declaration -> declaration.getVariables().stream())
                        .filter(variable -> variable.getNameAsString().equals(flag.name()))
                        .findFirst());
        Optional<Matcher> placeholder = field.flatMap(this::valuePlaceholder);
        String expected = String.valueOf(flag.disablesWhen());

        // В настройках рабочей среды: по точному ключу из @Value либо по последней части ключа
        String wanted = ConfigKeys.normalize(placeholder.map(matcher -> matcher.group(1).trim()).orElse(flag.name()));
        boolean isConfigured = false;
        for (ConfigFile file : configFiles) {
            if (file.isNonProduction()) {
                continue;
            }
            for (ConfigProperty property : file.properties()) {
                String key = ConfigKeys.normalize(property.key());
                boolean matches = placeholder.isPresent()
                        ? key.equals(wanted)
                        : key.substring(key.lastIndexOf('.') + 1).equals(wanted);
                if (!matches) {
                    continue;
                }
                isConfigured = true;
                if (property.value().trim().equalsIgnoreCase(expected)) {
                    return Optional.of("ее включает настройка '" + property.key() + "=" + property.value().trim() + "' ("
                            + file.path().getFileName() + ":" + property.line() + ")");
                }
            }
        }
        if (isConfigured) {
            return Optional.empty();
        }

        // В настройках флага нет: действует значение по умолчанию
        String byDefault = placeholder.map(matcher -> matcher.group(2))
                .or(() -> field.flatMap(VariableDeclarator::getInitializer)
                        .filter(Expression::isBooleanLiteralExpr)
                        .map(Expression::toString))
                .orElse("");
        return byDefault.trim().equalsIgnoreCase(expected) && (TRUE.equals(expected) || FALSE.equals(expected))
                ? Optional.of("флаг '" + flag.name() + "' по умолчанию равен " + expected
                        + " и в настройках не переопределен")
                : Optional.empty();
    }

    private Optional<Matcher> valuePlaceholder(VariableDeclarator variable) {
        return variable.getParentNode()
                .filter(parent -> parent instanceof FieldDeclaration)
                .flatMap(parent -> ((FieldDeclaration) parent).getAnnotations().stream()
                        .filter(annotation -> annotation.getNameAsString().equals(VALUE))
                        .map(AnnotationExpr::toString)
                        .map(PLACEHOLDER::matcher)
                        .filter(Matcher::find)
                        .findFirst());
    }

    private Violation report(SourceFile sourceFile, Disabling disabling, String reason) {
        int line = disabling.node().getBegin().map(position -> position.line).orElse(1);
        String condition = reason.isEmpty() ? "" : " Отключение стоит под условием, и " + reason + ".";
        return violation(sourceFile.path(), line,
                "Проверка SSL отключена (" + disabling.source() + "): соединение примет любой сертификат, и тот, кто"
                        + " встанет между приложением и сервером, прочитает и подменит данные; добавьте нужный"
                        + " сертификат в truststore вместо отключения проверки." + condition);
    }

    private boolean alwaysTrue(LambdaExpr lambda) {
        return lambda.getExpressionBody()
                .filter(body -> body.isBooleanLiteralExpr() && body.asBooleanLiteralExpr().getValue())
                .isPresent();
    }
}
