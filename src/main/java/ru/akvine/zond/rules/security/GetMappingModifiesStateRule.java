package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.RuleParameter;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CallChains;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.Handlers;
import ru.akvine.zond.rules.support.Jmix;
import ru.akvine.zond.rules.support.Mappings;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.StringLiterals;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Обработчик GET, который меняет данные. GET считается безопасным: его повторяют прокси и браузеры,
 * по ссылке переходят поисковые роботы и предзагрузка страниц, а защита от CSRF его не проверяет.
 */
@Component
public class GetMappingModifiesStateRule extends AbstractRule implements ProjectRule {
    private static final RuleParameter MAX_CALL_DEPTH = new RuleParameter(
            "max-call-depth", 3, "На сколько вызовов вглубь от обработчика искать запись в БД; 0 - только в нем самом");
    private static final String GET = "GET";

    private static final Pattern WRITE_METHOD = Pattern.compile(
            "^(save|saveAll|saveAndFlush|saveAllAndFlush|delete\\w*|remove\\w*|persist|merge|insert\\w*|update\\w*)$");
    private static final Set<String> ENTITY_MANAGER_WRITES = Set.of("persist", "merge", "remove");
    private static final Set<String> JDBC_WRITES = Set.of("update", "batchUpdate");
    private static final Set<String> DATA_MANAGER_WRITES = Set.of("save", "remove");
    private static final Pattern ENTITY_MANAGER = Pattern.compile("(?i).*entitymanager$|^em$");
    private static final Pattern JDBC_TEMPLATE = Pattern.compile("(?i).*jdbc(template|operations)$");
    private static final Pattern SELECT = Pattern.compile("^\\s*(select|with)\\b.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // Запись для учета, а не изменение данных пользователя: журнал, история, счетчик просмотров
    private static final Set<String> BOOKKEEPING = Set.of(
            "audit", "log", "logs", "history", "journal", "stat", "stats", "statistic", "statistics", "metric",
            "metrics", "event", "events", "view", "views", "visit", "visits", "access", "trace", "session",
            "sessions", "token", "tokens");
    // auditLogRepository -> audit, Log, Repository
    private static final Pattern WORD_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|_");
    // Ссылки из писем и возвраты от внешних систем открываются браузером: кроме GET им нечем быть
    private static final Pattern BY_LINK = Pattern.compile(
            "(?i).*(confirm|verify|verif|activate|unsubscribe|callback|redirect|oauth|login|logout|sso|return|webhook).*");

    @Override
    public String code() {
        return RuleCodes.GET_MAPPING_MODIFIES_STATE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет обработчики GET-запросов, которые меняют данные в БД";
    }

    @Override
    public List<RuleParameter> parameters() {
        return List.of(MAX_CALL_DEPTH);
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        CallGraph graph = CallGraph.of(sourceFiles);
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodDeclaration handler : sourceFile.unit().findAll(MethodDeclaration.class)) {
                // Адрес может быть объявлен в интерфейсе, который реализует контроллер
                Optional<AnnotationExpr> mapping = Handlers.of(handler, classes).map(Handlers.Handler::mapping);
                boolean isGet = mapping.filter(annotation -> GET.equals(Mappings.httpMethod(annotation))).isPresent();
                if (isGet && !isOpenedByLink(handler, mapping.get())) {
                    check(sourceFile, handler, graph, violations);
                }
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    private void check(SourceFile sourceFile, MethodDeclaration handler, CallGraph graph, List<Violation> violations) {
        for (Node node : handler.getBody().get().findAll(Node.class)) {
            Optional<String> write = describeWrite(node);
            if (write.isPresent()) {
                violations.add(violation(sourceFile, node, message(handler, write.get())));
                return;
            }
        }
        for (CallGraph.Call call : graph.callsFrom(handler)) {
            if (call.target() == handler) {
                continue;
            }
            Optional<CallChains.Found> found = CallChains.find(
                    graph, call.target(), value(MAX_CALL_DEPTH), method -> false, this::describeWrite);
            if (found.isPresent()) {
                violations.add(violation(sourceFile, call.site(),
                        message(handler, found.get().operation() + " (через вызов " + found.get().chain() + ")"))
                        .withConfidence(found.get().confidence(graph.isExact(call.site()))));
                return;
            }
        }
    }

    private String message(MethodDeclaration handler, String write) {
        return "Обработчик GET '" + handler.getNameAsString() + "' меняет данные (" + write + "): GET-запрос"
                + " повторяют прокси и браузеры, по ссылке переходят роботы и предзагрузка страниц, а защита"
                + " от CSRF его не проверяет - данные изменятся без участия пользователя; перенесите изменение"
                + " в POST, PUT или DELETE";
    }

    private Optional<String> describeWrite(Node node) {
        if (!(node instanceof MethodCallExpr call) || call.getScope().isEmpty()) {
            return Optional.empty();
        }
        Expression scope = call.getScope().get();
        String receiver = MethodCalls.receiverName(scope);
        String name = call.getNameAsString();
        if (isBookkeeping(receiver)) {
            return Optional.empty();
        }
        boolean writes = Repositories.isRepositoryCall(call) && WRITE_METHOD.matcher(name).matches()
                || ENTITY_MANAGER.matcher(receiver).matches() && ENTITY_MANAGER_WRITES.contains(name)
                || JDBC_TEMPLATE.matcher(receiver).matches() && JDBC_WRITES.contains(name) && !isSelect(call)
                || Jmix.isDataManagerCall(call, DATA_MANAGER_WRITES);
        return writes ? Optional.of(receiver + "." + name) : Optional.empty();
    }

    private boolean isBookkeeping(String receiver) {
        return WORD_BOUNDARY.splitAsStream(receiver).anyMatch(word -> BOOKKEEPING.contains(word.toLowerCase()));
    }

    private boolean isSelect(MethodCallExpr call) {
        return !call.getArguments().isEmpty()
                && StringLiterals.textOf(call.getArgument(0)).filter(sql -> SELECT.matcher(sql).matches()).isPresent();
    }

    private boolean isOpenedByLink(MethodDeclaration handler, AnnotationExpr mapping) {
        return BY_LINK.matcher(handler.getNameAsString()).matches()
                || Mappings.paths(mapping).stream().anyMatch(path -> BY_LINK.matcher(path).matches());
    }
}
