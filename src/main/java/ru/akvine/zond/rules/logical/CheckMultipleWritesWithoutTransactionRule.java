package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
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
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.SpringBeans;
import ru.akvine.zond.rules.support.TestClasses;
import ru.akvine.zond.rules.support.TransactionalAnnotations;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckMultipleWritesWithoutTransactionRule extends AbstractRule implements ProjectRule {
    private static final RuleParameter MAX_CALL_DEPTH = new RuleParameter(
            "max-call-depth", 3, "На сколько вызовов вглубь искать записи во вспомогательных методах класса; 0 - не искать");

    // save, saveAll, deleteById, updateStatus и т.п.
    private static final List<String> WRITE_PREFIXES =
            List.of("save", "delete", "update", "insert", "remove", "persist", "merge");
    private static final int MIN_WRITES = 2;

    @Override
    public List<RuleParameter> parameters() {
        return List.of(MAX_CALL_DEPTH);
    }

    @Override
    public String code() {
        return RuleCodes.CHECK_MULTIPLE_WRITES_WITHOUT_TRANSACTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет методы бинов, которые делают несколько записей в БД без общей транзакции";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        CallGraph graph = CallGraph.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                if (!SpringBeans.isBean(type) || TestClasses.isInside(type)) {
                    continue;
                }
                for (MethodDeclaration method : type.getMethods()) {
                    Set<String> writes = isCandidate(method, graph) ? findWrites(method, graph) : Set.of();
                    if (writes.size() >= MIN_WRITES) {
                        violations.add(violation(sourceFile, method,
                                "Метод '" + method.getNameAsString() + "' делает несколько записей в БД без общей"
                                        + " транзакции (" + String.join(", ", writes) + "): если вторая не удастся,"
                                        + " первая останется; объедините их одним @Transactional"));
                    }
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
        return ErrorType.LOGICAL;
    }

    // Приватный метод оценивается вместе с тем, кто его вызывает; метод, который вызывают только
    // из транзакции, уже выполняется в ней
    private boolean isCandidate(MethodDeclaration method, CallGraph graph) {
        if (method.isPrivate() || method.getBody().isEmpty() || isTransactional(method)) {
            return false;
        }
        List<CallGraph.Call> callers = graph.callsTo(method);
        return callers.isEmpty() || !callers.stream().allMatch(call -> isTransactional(call.caller()));
    }

    private Set<String> findWrites(MethodDeclaration method, CallGraph graph) {
        Set<String> writes = new LinkedHashSet<>();
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            describeWrite(call).ifPresent(writes::add);
        }
        // Записи во вспомогательных методах того же класса - часть этого же действия. Вызовы других бинов
        // не считаем: у них свои границы транзакций, и судить о них отсюда нельзя
        Node owner = method.getParentNode().orElse(null);
        for (CallGraph.Call call : graph.callsFrom(method)) {
            if (call.target() != method) {
                CallChains.find(graph, call.target(), value(MAX_CALL_DEPTH),
                                callee -> callee.getParentNode().orElse(null) != owner, this::describeWrite)
                        .ifPresent(found -> writes.add(found.operation()));
            }
        }
        return writes;
    }

    private Optional<String> describeWrite(Node node) {
        if (!(node instanceof MethodCallExpr call) || !Repositories.isRepositoryCall(call)) {
            return Optional.empty();
        }
        String name = call.getNameAsString();
        boolean isWrite = WRITE_PREFIXES.stream().anyMatch(prefix -> name.startsWith(prefix)
                && (name.length() == prefix.length() || Character.isUpperCase(name.charAt(prefix.length()))));
        return isWrite ? Optional.of(call.getScope().get() + "." + name) : Optional.empty();
    }

    private boolean isTransactional(MethodDeclaration method) {
        return TransactionalAnnotations.findEffective(method).isPresent();
    }
}
