package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.RuleParameter;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Branches;
import ru.akvine.zond.rules.support.CallChains;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.ProjectWords;
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.RepositoryEntities;
import ru.akvine.zond.rules.support.SpringBeans;
import ru.akvine.zond.rules.support.TestClasses;
import ru.akvine.zond.rules.support.TransactionScopes;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class MultipleWritesWithoutTransactionRule extends AbstractRule implements ProjectRule {
    private static final RuleParameter MAX_CALL_DEPTH = new RuleParameter(
            "max-call-depth", 3, "На сколько вызовов вглубь искать записи во вспомогательных методах класса; 0 - не искать");

    // save, saveAll, deleteById, updateStatus и т.п.
    private static final List<String> WRITE_PREFIXES =
            List.of("save", "delete", "update", "insert", "remove", "persist", "merge");
    private static final int MIN_WRITES = 2;
    private static final Set<String> MONGO_TRANSACTION_MANAGERS =
            Set.of("MongoTransactionManager", "ReactiveMongoTransactionManager");

    @Override
    public List<RuleParameter> parameters() {
        return List.of(MAX_CALL_DEPTH);
    }

    @Override
    public String code() {
        return RuleCodes.MULTIPLE_WRITES_WITHOUT_TRANSACTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет методы бинов, которые делают несколько записей в БД без общей транзакции";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        CallGraph graph = CallGraph.of(sourceFiles);
        Stores stores = new Stores(ProjectClasses.of(sourceFiles),
                ProjectWords.hasAny(sourceFiles, MONGO_TRANSACTION_MANAGERS));
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration type : sourceFile.findAll(ClassOrInterfaceDeclaration.class)) {
                if (!SpringBeans.isBean(type) || TestClasses.isInside(type)) {
                    continue;
                }
                for (MethodDeclaration method : type.getMethods()) {
                    Set<String> writes = isCandidate(method, graph)
                            ? writtenTogether(findWrites(method, graph, stores))
                            : Set.of();
                    if (writes.size() >= MIN_WRITES) {
                        String text = "Метод '" + method.getNameAsString() + "' делает несколько записей в БД без общей"
                                + " транзакции (" + String.join(", ", writes) + "): если вторая не удастся,"
                                + " первая останется; объедините их одним @Transactional";
                        // CommandLineRunner.run, @PostConstruct, @EventListener(ApplicationReadyEvent.class): начальные
                        // данные заливают один раз при запуске, и сбой виден сразу - приложение не поднимется
                        violations.add(CodeContexts.isStartupMethod(method)
                                ? violation(sourceFile, method, text + ". Метод выполняется один раз при запуске"
                                + " приложения, поэтому это подозрение").withConfidence(Confidence.SUSPICION)
                                : violation(sourceFile, method, text));
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

    /**
     * @param operation что записывается: orderRepository.save
     * @param site      место в проверяемом методе, с которого запись начинается
     */
    private record Write(String operation, Node site) {
    }

    /**
     * @param classes           классы проекта: по ним находится интерфейс репозитория и его сущность
     * @param mongoTransactions объявлен ли в проекте MongoTransactionManager
     */
    private record Stores(ProjectClasses classes, boolean mongoTransactions) {
    }

    // Приватный метод оценивается вместе с тем, кто его вызывает; метод, который вызывают только
    // из транзакции, уже выполняется в ней. Транзакцию дает и аннотация (своя, предка, составная), и
    // transactionTemplate.execute(...) выше по цепочке вызовов, и ручное управление через getTransaction / commit
    private boolean isCandidate(MethodDeclaration method, CallGraph graph) {
        if (method.isPrivate() || method.getBody().isEmpty()) {
            return false;
        }
        return !TransactionScopes.isInTransaction(method.getBody().get(), graph);
    }

    private List<Write> findWrites(MethodDeclaration method, CallGraph graph, Stores stores) {
        List<Write> writes = new ArrayList<>();
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            describeWrite(call, stores)
                    .filter(operation -> isOutsideCallback(call))
                    .ifPresent(operation -> writes.add(new Write(operation, call)));
        }
        // Записи во вспомогательных методах того же класса - часть этого же действия. Вызовы других бинов
        // не считаем: у них свои границы транзакций, и судить о них отсюда нельзя
        Node owner = method.getParentNode().orElse(null);
        for (CallGraph.Call call : graph.callsFrom(method)) {
            if (call.target() != method && isOutsideCallback(call.site())) {
                CallChains.find(graph, call.target(), value(MAX_CALL_DEPTH),
                                callee -> callee.getParentNode().orElse(null) != owner
                                        || TransactionScopes.isTransactional(callee),
                                node -> describeWrite(node, stores))
                        .ifPresent(found -> writes.add(new Write(found.operation(), call.site())));
            }
        }
        return writes;
    }

    // transactionTemplate.execute(status -> { a.save(x); b.save(y); }): транзакция уже есть, и ее границы
    // автор выбрал сам
    private boolean isOutsideCallback(Node site) {
        return TransactionScopes.enclosingCallback(site).isEmpty();
    }

    /**
     * Записи в разных ветках if / else либо до и после раннего return за один вызов вместе не выполняются:
     * общая транзакция им не нужна
     *
     * @return записи, у которых есть пара - другая запись, выполняемая в том же проходе метода
     */
    private Set<String> writtenTogether(List<Write> writes) {
        Set<String> together = new LinkedHashSet<>();
        for (Write write : writes) {
            for (Write other : writes) {
                if (!other.operation().equals(write.operation())
                        && !Branches.areExclusive(write.site(), other.site())) {
                    together.add(write.operation());
                    break;
                }
            }
        }
        return together;
    }

    private Optional<String> describeWrite(Node node, Stores stores) {
        if (!(node instanceof MethodCallExpr call) || !Repositories.isRepositoryCall(call)) {
            return Optional.empty();
        }
        String name = call.getNameAsString();
        boolean isWrite = WRITE_PREFIXES.stream().anyMatch(prefix -> name.startsWith(prefix)
                && (name.length() == prefix.length() || Character.isUpperCase(name.charAt(prefix.length()))));
        return isWrite && hasTransactions(call.getScope().get(), stores)
                ? Optional.of(call.getScope().get() + "." + name)
                : Optional.empty();
    }

    // У Cassandra, Elasticsearch и Redis транзакций нет, у MongoDB - только с объявленным менеджером транзакций:
    // совет "объедините одним @Transactional" для них невыполним
    private boolean hasTransactions(Expression scope, Stores stores) {
        Optional<ClassOrInterfaceDeclaration> repository = RepositoryEntities.repositoryOf(scope, stores.classes());
        if (repository.isEmpty()) {
            return true;
        }
        List<Node> declarations = new ArrayList<>();
        declarations.add(repository.get());
        RepositoryEntities.entityOf(repository.get(), stores.classes()).ifPresent(declarations::add);
        Repositories.Store store = Repositories.storeOf(declarations);
        return store == Repositories.Store.TRANSACTIONAL
                || store == Repositories.Store.MONGO && stores.mongoTransactions();
    }
}
