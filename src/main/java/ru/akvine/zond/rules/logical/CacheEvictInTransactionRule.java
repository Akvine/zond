package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CacheAnnotations;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.Messaging;
import ru.akvine.zond.rules.support.ProjectWords;
import ru.akvine.zond.rules.support.TransactionalAnnotations;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Кеш сбрасывают до того, как транзакция с новыми данными закоммичена. В этот промежуток параллельный запрос
 * читает из базы старое значение и кладет его обратно в кеш - уже надолго.
 */
@Component
public class CacheEvictInTransactionRule extends AbstractRule implements ProjectRule {
    // Менеджер кеша, который сам откладывает изменения до коммита
    private static final Set<String> TRANSACTION_AWARE = Set.of(
            "TransactionAwareCacheManagerProxy", "TransactionAwareCacheDecorator", "setTransactionAware",
            "transactionAware");
    private static final String FIX = "; сбрасывайте кеш после коммита (@TransactionalEventListener) либо сделайте"
            + " менеджер кеша транзакционным (TransactionAwareCacheManagerProxy, transactionAware())";

    @Override
    public String code() {
        return RuleCodes.CACHE_EVICT_IN_TRANSACTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет сброс кеша (@CacheEvict, @CachePut) внутри транзакции, до ее коммита";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        List<Violation> violations = new ArrayList<>();
        if (ProjectWords.hasAny(sourceFiles, TRANSACTION_AWARE)) {
            return violations;
        }
        CallGraph graph = CallGraph.of(sourceFiles);
        Map<CompilationUnit, SourceFile> files = new IdentityHashMap<>();
        sourceFiles.forEach(sourceFile -> files.put(sourceFile.unit(), sourceFile));
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
                List<AnnotationExpr> evictions = CacheAnnotations.find(method, CacheAnnotations.EVICTING);
                if (evictions.isEmpty()) {
                    continue;
                }
                String annotation = "@" + evictions.get(0).getNameAsString();
                if (writesInTransaction(method)) {
                    violations.add(violation(sourceFile, method,
                            annotation + " и @Transactional на одном методе '" + method.getNameAsString()
                                    + "': кеш меняется, когда метод вернул управление, а транзакция в этот момент"
                                    + " может быть еще не закоммичена - параллельный запрос прочитает из базы"
                                    + " старые данные и снова положит их в кеш" + FIX));
                    continue;
                }
                reportCallers(method, annotation, graph, files, violations);
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

    // Метод сам без транзакции, но вызван из чужой: его кеш меняется посреди нее
    private void reportCallers(
            MethodDeclaration method, String annotation, CallGraph graph, Map<CompilationUnit, SourceFile> files,
            List<Violation> violations) {
        for (CallGraph.Call call : graph.callsTo(method)) {
            // Вызов из своего же класса идет мимо прокси: аннотация кеша при нем не срабатывает вовсе
            boolean sameClass = Messaging.ownerOf(call.caller())
                    .filter(owner -> Messaging.ownerOf(method).filter(own -> own == owner).isPresent())
                    .isPresent();
            Optional<SourceFile> file = call.site().findCompilationUnit().map(files::get);
            if (!sameClass && writesInTransaction(call.caller()) && file.isPresent()) {
                violations.add(violation(file.get(), call.site(),
                        "Метод '" + method.getNameAsString() + "' меняет кеш (" + annotation + "), а вызван внутри"
                                + " транзакции метода '" + call.caller().getNameAsString() + "', до ее коммита:"
                                + " параллельный запрос прочитает из базы старые данные и снова положит их в кеш"
                                + FIX));
            }
        }
    }

    // Транзакция только на чтение данных не меняет: расходиться кешу не с чем
    private boolean writesInTransaction(MethodDeclaration method) {
        return !method.isPrivate()
                && TransactionalAnnotations.findEffective(method)
                .filter(transaction -> !TransactionalAnnotations.isReadOnly(transaction))
                .isPresent();
    }
}
