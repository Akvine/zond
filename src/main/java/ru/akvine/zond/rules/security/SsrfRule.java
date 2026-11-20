package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractTaintRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.StringLiterals;
import ru.akvine.zond.rules.support.Taint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class SsrfRule extends AbstractTaintRule {
    // Методы, первым аргументом которых идет адрес: RestTemplate, WebClient, RestClient
    // Значение пришло ответом другого сервиса, в том числе через свойство объекта
    private static final Pattern EXTERNAL_ORIGIN = Pattern.compile(
            "^(getForObject|getForEntity|postForObject|postForEntity|patchForObject|exchange|bodyToMono|bodyToFlux)\\(");
    private static final Set<String> REQUEST_METHODS = Set.of(
            "getForObject", "getForEntity", "postForObject", "postForEntity", "postForLocation", "patchForObject",
            "exchange", "uri");
    private static final Set<String> ADDRESS_TYPES = Set.of("URL", "URI");
    private static final String URI = "URI";
    private static final String CREATE = "create";
    // С этих знаков начинается то, что идет в адресе после узла: путь, параметры, якорь
    private static final String AFTER_HOST = "/?&#=";
    // "https://" + host: в литерале только схема (и, возможно, начало узла) - узел дописывает клиент
    private static final Pattern SCHEME_ONLY = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://[^/?#]*$");
    private static final int INITIALIZER_DEPTH = 2;
    // Только эти методы URL открывают соединение; у URI таких нет вовсе
    private static final Set<String> CONNECTING_METHODS = Set.of("openConnection", "openStream", "getContent");
    // Разбор адреса без обращения по нему
    private static final Set<String> INSPECTION_METHODS = Set.of(
            "getHost", "getScheme", "getPort", "getPath", "getQuery", "getAuthority", "getFragment", "getUserInfo",
            "getRawPath", "getRawQuery", "getProtocol", "getDefaultPort", "isAbsolute", "isOpaque", "equals");

    @Override
    public String code() {
        return RuleCodes.SSRF_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет HTTP-запросы на адрес, взятый из данных запроса (SSRF)";
    }

    @Override
    protected List<Violation> check(SourceFile sourceFile, Taint taint) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.findAll(MethodCallExpr.class)) {
            boolean takesAddress = REQUEST_METHODS.contains(call.getNameAsString())
                    || MethodCalls.isCallOn(call, URI, CREATE);
            if (takesAddress && !call.getArguments().isEmpty() && !isOnlyInspected(call)) {
                report(sourceFile, taint, call, call.getArgument(0), violations);
            }
        }
        for (ObjectCreationExpr creation : sourceFile.findAll(ObjectCreationExpr.class)) {
            // new URI(link); без использования результата - проверка формата адреса, а не обращение по нему
            if (ADDRESS_TYPES.contains(creation.getType().getNameAsString()) && !creation.getArguments().isEmpty()
                    && !(creation.getParentNode().orElse(null) instanceof ExpressionStmt) && !isOnlyInspected(creation)) {
                report(sourceFile, taint, creation, creation.getArgument(0), violations);
            }
        }
        violations.sort(Comparator.comparingInt(Violation::line));
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    private void report(
            SourceFile sourceFile, Taint taint, Node node, Expression address, List<Violation> violations) {
        Host host = hostOf(address, taint, INITIALIZER_DEPTH);
        if (host == Host.FIXED) {
            return;
        }
        // Адрес из ответа того же API (куда загрузить файл, ссылка на следующую страницу) - обычная работа с API
        taint.findSource(address)
                .filter(source -> source.kind() != Taint.Kind.EXTERNAL && !EXTERNAL_ORIGIN.matcher(source.text()).find())
                .ifPresent(source -> {
                    Violation violation = violation(sourceFile, node,
                            "Адрес запроса берется из данных клиента '" + source + "': так можно заставить сервер"
                                    + " обратиться к внутренним адресам (SSRF); сверяйте узел со списком разрешенных");
                    // base + key: начало адреса задано не клиентом, и подменить узел можно, только если оно
                    // не кончается на "/" (https://files.example.org + "@evil.org/x")
                    violations.add(host == Host.AFTER_BASE
                            ? violation.withConfidence(Confidence.SUSPICION)
                            : violation);
                });
    }

    // Кто задает узел в адресе: код, клиент либо начало адреса из настроек, после которого дописаны данные клиента
    private enum Host {
        FIXED, AFTER_BASE, CLIENT
    }

    /**
     * "https://api.example.org/users/" + id, baseUrl + "/async-result/" + taskId, url + "?" + query: узел задан
     * в коде или настройках, клиент влияет только на путь и параметры. "https://" + host - узел задает клиент
     */
    private Host hostOf(Expression address, Taint taint, int depth) {
        Expression value = Nodes.unwrap(address);
        if (value.isConditionalExpr()) {
            Host then = branchHost(value.asConditionalExpr().getThenExpr(), taint, depth);
            Host otherwise = branchHost(value.asConditionalExpr().getElseExpr(), taint, depth);
            return then.ordinal() >= otherwise.ordinal() ? then : otherwise;
        }
        if (value.isNameExpr() && depth > 0) {
            // String target = url + "?" + param; new URL(target): смотрим, как адрес собран
            Optional<Expression> initializer = LocalTypes.findInitializer(value);
            if (initializer.isPresent()) {
                return hostOf(initializer.get(), taint, depth - 1);
            }
        }
        Expression first = leftmost(value);
        Optional<String> literal = StringLiterals.textOf(first);
        if (literal.isPresent()) {
            return SCHEME_ONLY.matcher(literal.get()).matches() && first != value ? Host.CLIENT : Host.FIXED;
        }
        if (first == value || taint.findSource(first).isPresent()) {
            return Host.CLIENT;
        }
        return partAfter(first).isPresent() ? Host.FIXED : Host.AFTER_BASE;
    }

    // Ветка без данных клиента узел не меняет
    private Host branchHost(Expression branch, Taint taint, int depth) {
        return taint.findSource(branch).isEmpty() ? Host.FIXED : hostOf(branch, taint, depth);
    }

    // Литерал сразу за первой частью склейки, с которого начинается путь, параметры или якорь: base + "/path/" + id
    private Optional<String> partAfter(Expression first) {
        Node parent = first.getParentNode().orElse(null);
        while (parent instanceof EnclosedExpr) {
            parent = parent.getParentNode().orElse(null);
        }
        if (!(parent instanceof BinaryExpr concatenation) || concatenation.getOperator() != BinaryExpr.Operator.PLUS) {
            return Optional.empty();
        }
        return StringLiterals.textOf(concatenation.getRight())
                .filter(text -> !text.isEmpty() && AFTER_HOST.indexOf(text.charAt(0)) >= 0);
    }

    /**
     * URI uri = new URI(state); if (uri.getHost() == null) ...; new URI(origin).getHost(); new URL(link).toURI():
     * адрес только разбирают и проверяют. Запрос по нему делает лишь URL - openConnection(), openStream(),
     * getContent() - либо тот, кому объект передали
     */
    private boolean isOnlyInspected(Expression address) {
        Node parent = address.getParentNode().orElse(null);
        if (parent instanceof MethodCallExpr call && call.getScope().filter(scope -> scope == address).isPresent()) {
            return !CONNECTING_METHODS.contains(call.getNameAsString()) && isResultInspected(call);
        }
        String variable = null;
        if (parent instanceof VariableDeclarator declarator) {
            variable = declarator.getNameAsString();
        } else if (parent instanceof AssignExpr assign && assign.getValue() == address && assign.getTarget().isNameExpr()) {
            variable = assign.getTarget().asNameExpr().getNameAsString();
        }
        if (variable == null) {
            return false;
        }
        String name = variable;
        Optional<Node> scope = address.findAncestor(CallableDeclaration.class).map(callable -> (Node) callable);
        return scope.isPresent() && scope.get().findAll(NameExpr.class).stream()
                .filter(usage -> usage.getNameAsString().equals(name))
                .filter(usage -> !(usage.getParentNode().orElse(null) instanceof AssignExpr assign && assign.getTarget() == usage))
                .allMatch(this::isInspection);
    }

    // new URI(origin).getHost() - разбор; new URL(link).toURI() как аргумент запроса - уже обращение
    private boolean isResultInspected(MethodCallExpr call) {
        if (INSPECTION_METHODS.contains(call.getNameAsString())) {
            return true;
        }
        return !(call.getParentNode().orElse(null) instanceof MethodCallExpr outer
                && outer.getArguments().contains(call));
    }

    private boolean isInspection(NameExpr usage) {
        Node parent = usage.getParentNode().orElse(null);
        if (parent instanceof MethodCallExpr call && call.getScope().filter(scope -> scope == usage).isPresent()) {
            return !CONNECTING_METHODS.contains(call.getNameAsString()) && isResultInspected(call);
        }
        return parent instanceof BinaryExpr comparison
                && (comparison.getLeft().isNullLiteralExpr() || comparison.getRight().isNullLiteralExpr());
    }

    private Expression leftmost(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        while (value.isBinaryExpr() && value.asBinaryExpr().getOperator() == BinaryExpr.Operator.PLUS) {
            value = Nodes.unwrap(value.asBinaryExpr().getLeft());
        }
        return value;
    }
}
