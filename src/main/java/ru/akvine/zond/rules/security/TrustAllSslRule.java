package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
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
import ru.akvine.zond.rules.support.LocalTypes;
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

    // Исключения, которыми проверка сертификата сообщает об отказе: перехватить и не пробросить - значит принять
    private static final Set<String> CERTIFICATE_FAILURES = Set.of(
            "CertificateException", "CertificateExpiredException", "CertificateNotYetValidException",
            "CertificateRevokedException", "CertPathValidatorException", "GeneralSecurityException", "Exception",
            "Throwable");
    // Вызовы, которыми сертификат проверяют: у другого менеджера доверия либо у самого сертификата
    private static final Set<String> VALIDATIONS = Set.of(
            "checkServerTrusted", "checkClientTrusted", "checkValidity", "verify", "validate");
    private static final String HOSTNAME_VERIFIER = "HostnameVerifier";
    // Отключения, которые касаются только сверки имени хоста с сертификатом
    private static final Pattern HOST_CHECK = Pattern.compile("(?i)hostname|^verify\\(");
    private static final String VERIFY = "verify";
    private static final String SSL_SESSION = "SSLSession";
    private static final int VERIFY_PARAMETERS = 2;

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

    /**
     * Флаг-переключатель, который класс получает параметром конструктора: отключена ли проверка, решает тот,
     * кто создает объект
     *
     * @param type       класс с отключением под флагом
     * @param parameters сколько параметров у конструктора
     * @param index      номер параметра, из которого берется флаг
     * @param place      где отключение записано: SecureConnections.java:118
     */
    private record Switch(String type, int parameters, int index, Flag flag, String source, String place) {
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
        List<Switch> switches = new ArrayList<>();
        List<Violation> violations = new ArrayList<>();
        context.sources().forEach(sourceFile -> violations.addAll(check(sourceFile, context.configFiles(), switches)));
        if (switches.isEmpty()) {
            return violations;
        }
        // new SecureConnections(keyStore, password, false): проверку отключает тот, кто передал флагу такое значение
        for (SourceFile sourceFile : context.sources()) {
            for (ObjectCreationExpr creation : sourceFile.findAll(ObjectCreationExpr.class)) {
                switchTurnedOffBy(creation, switches).ifPresent(found -> violations.add(report(sourceFile,
                        new Disabling(creation, "new " + found.type() + "(...) с " + found.flag().name() + "="
                                + found.flag().disablesWhen() + " включает " + found.source() + " в "
                                + found.place()), "")));
            }
        }
        return violations;
    }

    // Один файл без настроек: отключение под условием тогда судится только по значению флага в коде
    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return check(sourceFile, List.of(), new ArrayList<>());
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

    private List<Violation> check(SourceFile sourceFile, List<ConfigFile> configFiles, List<Switch> switches) {
        List<Violation> violations = new ArrayList<>();
        for (Disabling disabling : findDisablings(sourceFile)) {
            Optional<Node> branch = enclosingBranch(disabling.node());
            if (branch.isEmpty()) {
                violations.add(report(sourceFile, disabling, ""));
                continue;
            }
            // Отключение стоит под условием: это переключатель. Ошибка это или нет, решает значение флага -
            // в настройках рабочей среды либо по умолчанию в коде
            Optional<Flag> flag = flagOf(branch.get(), disabling.node());
            Optional<String> reason = flag.flatMap(found -> describeEnabled(found, disabling.node(), configFiles));
            reason.ifPresent(text -> violations.add(report(sourceFile, disabling, text)));
            if (reason.isEmpty() && flag.isPresent()) {
                switches.addAll(constructorSwitches(flag.get(), disabling, sourceFile));
            }
        }
        violations.sort(Comparator.comparingInt(Violation::line));
        return violations;
    }

    // Флаг - поле, которое конструктор заполняет из своего параметра: this.verifyHostname = verifyHostname
    private List<Switch> constructorSwitches(Flag flag, Disabling disabling, SourceFile sourceFile) {
        Optional<TypeDeclaration> type = disabling.node().findAncestor(TypeDeclaration.class);
        if (type.isEmpty()) {
            return List.of();
        }
        List<Switch> switches = new ArrayList<>();
        String place = sourceFile.path().getFileName() + ":"
                + disabling.node().getBegin().map(position -> position.line).orElse(1);
        TypeDeclaration<?> owner = type.get();
        for (ConstructorDeclaration constructor : owner.getConstructors()) {
            for (AssignExpr assignment : constructor.findAll(AssignExpr.class)) {
                boolean fillsFlag = assignment.getTarget().toString().replace("this.", "").equals(flag.name())
                        && assignment.getValue().isNameExpr();
                if (!fillsFlag) {
                    continue;
                }
                String source = assignment.getValue().asNameExpr().getNameAsString();
                for (int index = 0; index < constructor.getParameters().size(); index++) {
                    if (constructor.getParameter(index).getNameAsString().equals(source)) {
                        switches.add(new Switch(type.get().getNameAsString(), constructor.getParameters().size(), index,
                                flag, disabling.source(), place));
                    }
                }
            }
        }
        return switches;
    }

    private Optional<Switch> switchTurnedOffBy(ObjectCreationExpr creation, List<Switch> switches) {
        for (Switch found : switches) {
            if (turnsOff(creation, found)) {
                return Optional.of(found);
            }
        }
        return Optional.empty();
    }

    private boolean turnsOff(ObjectCreationExpr creation, Switch found) {
        if (!creation.getType().getNameAsString().equals(found.type())
                || creation.getArguments().size() != found.parameters()) {
            return false;
        }
        Expression argument = Nodes.unwrap(creation.getArgument(found.index()));
        return argument.isBooleanLiteralExpr()
                && argument.asBooleanLiteralExpr().getValue() == found.flag().disablesWhen();
    }

    private List<Disabling> findDisablings(SourceFile sourceFile) {
        List<Disabling> found = new ArrayList<>();
        for (MethodDeclaration method : sourceFile.findAll(MethodDeclaration.class)) {
            boolean isEmptyCheck = TRUST_CHECKS.contains(method.getNameAsString())
                    && method.getBody().filter(body -> body.getStatements().isEmpty()).isPresent();
            if (isEmptyCheck) {
                found.add(new Disabling(method, "пустой " + method.getNameAsString() + "(...)"));
            }
            // Сертификат отвергнут, а метод завершается как ни в чем не бывало
            if (TRUST_CHECKS.contains(method.getNameAsString())) {
                swallowedRejection(method).ifPresent(clause -> found.add(new Disabling(clause,
                        method.getNameAsString() + "(...) перехватывает " + clause.getParameter().getType()
                                + " и не пробрасывает его")));
            }
            if (acceptsAnyHost(method)) {
                found.add(new Disabling(method, "verify(...) всегда возвращает true"));
            }
            // HostnameVerifier hostnameVerifier() { return (host, session) -> true; }
            if (HOSTNAME_VERIFIER.equals(LocalTypes.typeName(method.getType()))) {
                method.findAll(ReturnStmt.class).stream()
                        .filter(statement -> statement.getExpression()
                                .map(Nodes::unwrap)
                                .filter(value -> value.isLambdaExpr() && alwaysTrue(value.asLambdaExpr()))
                                .isPresent())
                        .forEach(statement -> found.add(new Disabling(statement,
                                method.getNameAsString() + "() возвращает HostnameVerifier (... -> true)")));
            }
        }
        for (SimpleName name : sourceFile.findAll(SimpleName.class)) {
            if (TRUST_ALL_NAMES.contains(name.getIdentifier())) {
                found.add(new Disabling(name, name.getIdentifier()));
            }
        }
        for (MethodCallExpr call : sourceFile.findAll(MethodCallExpr.class)) {
            if (VERIFIER_SETTERS.contains(call.getNameAsString())
                    && call.getArguments().stream().anyMatch(this::acceptsEverything)) {
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
            inThen = Nodes.isInside(node, statement.getThenStmt()) || statement.getThenStmt() == node;
        } else {
            ConditionalExpr conditional = (ConditionalExpr) branch;
            condition = conditional.getCondition();
            inThen = Nodes.isInside(node, conditional.getThenExpr()) || conditional.getThenExpr() == node;
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
        // Отключена только сверка имени: сертификат проверяется, но подойдет выданный кому угодно
        boolean hostOnly = HOST_CHECK.matcher(disabling.source()).find();
        return violation(sourceFile.path(), line,
                "Проверка SSL отключена (" + disabling.source() + "): соединение примет "
                        + (hostOnly ? "сертификат, выданный на любое другое имя" : "любой сертификат")
                        + ", и тот, кто встанет между приложением и сервером, прочитает и подменит данные; "
                        + (hostOnly
                        ? "обращайтесь к серверу по имени из его сертификата либо добавьте это имя в сертификат"
                        : "добавьте нужный сертификат в truststore")
                        + " вместо отключения проверки." + condition);
    }

    // (host, session) -> true прямо в аргументе либо в поле или переменной, которую передали по имени
    private boolean acceptsEverything(Expression argument) {
        Expression value = Nodes.unwrap(argument);
        if (value.isLambdaExpr()) {
            return alwaysTrue(value.asLambdaExpr());
        }
        return (value.isNameExpr() || value.isFieldAccessExpr()) && LocalTypes.findDeclaration(value)
                .filter(declaration -> declaration instanceof VariableDeclarator)
                .flatMap(declaration -> ((VariableDeclarator) declaration).getInitializer())
                .map(Nodes::unwrap)
                .filter(initializer -> initializer.isLambdaExpr() && alwaysTrue(initializer.asLambdaExpr()))
                .isPresent();
    }

    // boolean verify(String hostname, SSLSession session) { return true; } - в своем классе или в анонимном
    private boolean acceptsAnyHost(MethodDeclaration method) {
        boolean isVerify = VERIFY.equals(method.getNameAsString())
                && method.getParameters().size() == VERIFY_PARAMETERS
                && SSL_SESSION.equals(LocalTypes.typeName(method.getParameter(1).getType()));
        return isVerify && method.getBody()
                .filter(body -> body.getStatements().size() == 1 && isReturnTrue(body.getStatement(0)))
                .isPresent();
    }

    /**
     * @return перехват отказа проверки, после которого метод завершается обычным образом: в блоке нет ни throw,
     * ни проверки у другого менеджера доверия, отказ которой дошел бы до вызывающего
     */
    private Optional<CatchClause> swallowedRejection(MethodDeclaration method) {
        for (CatchClause clause : method.findAll(CatchClause.class)) {
            boolean catchesRejection = clause.getParameter().getType().findAll(ClassOrInterfaceType.class).stream()
                    .anyMatch(type -> CERTIFICATE_FAILURES.contains(type.getNameAsString()));
            // Внешний перехват, внутри которого запасная проверка тоже перехвачена, - то же самое место
            boolean isOutermost = clause.findAncestor(CatchClause.class)
                    .filter(outer -> Nodes.isInside(outer, method))
                    .isEmpty();
            // Перехватывают именно проверку, а не запись в лог рядом с ней
            boolean guardsCheck = clause.getParentNode()
                    .filter(parent -> parent instanceof TryStmt)
                    .map(parent -> ((TryStmt) parent).getTryBlock())
                    .filter(block -> block.findAll(MethodCallExpr.class).stream()
                            .anyMatch(call -> VALIDATIONS.contains(call.getNameAsString())))
                    .isPresent();
            if (catchesRejection && isOutermost && guardsCheck && clause.findAll(ThrowStmt.class).isEmpty()
                    && !delegatesCheck(clause)) {
                return Optional.of(clause);
            }
        }
        return Optional.empty();
    }

    // catch (CertificateException e) { defaultManager.checkServerTrusted(chain, authType); } - отказ запасного
    // менеджера уходит наружу, если его не перехватывают тут же
    private boolean delegatesCheck(CatchClause clause) {
        return clause.getBody().findAll(MethodCallExpr.class).stream()
                .filter(call -> TRUST_CHECKS.contains(call.getNameAsString()))
                .anyMatch(call -> call.findAncestor(TryStmt.class)
                        .filter(nested -> Nodes.isInside(nested, clause.getBody())
                                && Nodes.isInside(call, nested.getTryBlock()))
                        .isEmpty());
    }

    private boolean alwaysTrue(LambdaExpr lambda) {
        boolean byExpression = lambda.getExpressionBody()
                .filter(body -> body.isBooleanLiteralExpr() && body.asBooleanLiteralExpr().getValue())
                .isPresent();
        // (host, session) -> { return true; }
        return byExpression || lambda.getBody() instanceof BlockStmt block
                && block.getStatements().size() == 1 && isReturnTrue(block.getStatement(0));
    }

    private boolean isReturnTrue(Statement statement) {
        return statement instanceof ReturnStmt result && result.getExpression()
                .map(Nodes::unwrap)
                .filter(value -> value.isBooleanLiteralExpr() && value.asBooleanLiteralExpr().getValue())
                .isPresent();
    }
}
