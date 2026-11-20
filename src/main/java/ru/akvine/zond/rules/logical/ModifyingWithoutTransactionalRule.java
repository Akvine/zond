package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.TestClasses;
import ru.akvine.zond.rules.support.TransactionScopes;
import ru.akvine.zond.rules.support.TransactionalAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class ModifyingWithoutTransactionalRule extends AbstractRule implements ProjectRule {
    private static final String MODIFYING = "Modifying";
    // Транзакцию для изменяющего запроса требует только JPA: Spring Data JDBC и R2DBC выполняют его и без нее
    private static final List<String> OTHER_STORES = List.of(
            "org.springframework.data.jdbc", "org.springframework.data.r2dbc", "org.springframework.data.relational",
            "org.springframework.data.mongodb", "org.springframework.data.neo4j", "org.springframework.data.cassandra",
            "org.springframework.data.couchbase", "org.springframework.data.elasticsearch");
    private static final String JPA = "org.springframework.data.jpa";

    @Override
    public String code() {
        return RuleCodes.MODIFYING_WITHOUT_TRANSACTIONAL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет @Modifying-запросы репозитория JPA, которые вызываются вне транзакции";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        CallGraph graph = CallGraph.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            if (isOtherStore(sourceFile.unit())) {
                continue;
            }
            for (MethodDeclaration method : sourceFile.findAll(MethodDeclaration.class)) {
                if (Annotations.has(method, MODIFYING) && !isTransactional(method)) {
                    check(sourceFile, method, graph).ifPresent(violations::add);
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
     * Запрос без своей транзакции - ошибка только там, где его вызывают вне транзакции. Обычно репозиторий
     * зовут из @Transactional-метода сервиса, и тогда запрос выполняется в его транзакции
     */
    private Optional<Violation> check(SourceFile sourceFile, MethodDeclaration method, CallGraph graph) {
        List<CallGraph.Call> calls = graph.callsTo(method).stream()
                .filter(call -> !TestClasses.isInside(call.site()))
                .toList();
        String start = "@Modifying-запрос '" + method.getNameAsString() + "' без @Transactional";
        String reason = ": свои методы репозитория Spring Data в транзакцию не оборачивает, при вызове вне"
                + " транзакции будет TransactionRequiredException";
        if (calls.isEmpty()) {
            return Optional.of(violation(sourceFile, method, start + reason + ". Вызовов запроса в проекте не найдено,"
                    + " поэтому это подозрение; добавьте @Transactional на метод или на репозиторий")
                    .withConfidence(Confidence.SUSPICION));
        }
        return calls.stream()
                .filter(call -> !TransactionScopes.isInTransaction(call.site(), graph))
                .findFirst()
                .map(call -> violation(sourceFile, method, start + " вызывается вне транзакции из '"
                        + describe(call.caller()) + "'" + reason + "; добавьте @Transactional на вызывающий метод"
                        + " либо на сам запрос"));
    }

    private String describe(MethodDeclaration caller) {
        String owner = caller.findAncestor(TypeDeclaration.class)
                .map(type -> ((TypeDeclaration<?>) type).getNameAsString() + ".")
                .orElse("");
        return owner + caller.getNameAsString();
    }

    // Аннотация на методе либо на самом репозитории
    private boolean isTransactional(MethodDeclaration method) {
        return TransactionalAnnotations.isPresent(method)
                || method.getParentNode()
                .filter(parent -> parent instanceof TypeDeclaration<?> type && TransactionalAnnotations.isPresent(type))
                .isPresent();
    }

    // import org.springframework.data.jdbc.repository.query.Modifying: одноименная аннотация другого хранилища
    private boolean isOtherStore(CompilationUnit unit) {
        List<String> imports = unit.getImports().stream().map(ImportDeclaration::getNameAsString).toList();
        return imports.stream().noneMatch(name -> name.startsWith(JPA))
                && imports.stream().anyMatch(name -> OTHER_STORES.stream().anyMatch(name::startsWith));
    }
}
