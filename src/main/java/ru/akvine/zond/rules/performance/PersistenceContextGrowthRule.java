package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.TestClasses;
import ru.akvine.zond.rules.support.TransactionalAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Пакетная обработка в одной транзакции. Контекст персистентности держит каждую прочитанную и сохраненную
 * сущность до конца транзакции. Если страницы или поток записей обходят внутри одного @Transactional-метода и
 * контекст не очищают, память растет с каждой страницей, а каждая синхронизация с базой заново проверяет все
 * накопленные сущности.
 */
@Component
public class PersistenceContextGrowthRule extends AbstractRule {
    // entityManager.clear(), entityManager.detach(order), session.evict(order)
    private static final Set<String> RELEASES = Set.of("clear", "detach", "evict");
    // repository.streamAll(), repository.streamByStatus(...), query.scroll()
    private static final Pattern STREAMING_READ = Pattern.compile("^(stream\\w*|scroll|getResultStream)$");
    private static final String CONSEQUENCE = " внутри одной транзакции без entityManager.clear(): контекст"
            + " персистентности держит каждую прочитанную сущность до конца транзакции - память растет с объемом"
            + " данных, а каждая синхронизация с базой (flush) заново проверяет все накопленные сущности. После"
            + " каждой порции вызывайте entityManager.flush() и entityManager.clear() либо обрабатывайте порцию в"
            + " отдельной транзакции";

    @Override
    public String code() {
        return RuleCodes.PERSISTENCE_CONTEXT_GROWTH_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет постраничный или потоковый обход таблицы внутри одной транзакции без очистки"
                + " контекста персистентности: память растет с объемом данных";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            if (method.isPrivate() || TransactionalAnnotations.findEffective(method).isEmpty() || TestClasses.isInside(method)
                    || releasesContext(method)) {
                continue;
            }
            // О методе сообщаем один раз - на первом обходе
            firstTraversal(method).ifPresent(traversal -> violations.add(violation(sourceFile, traversal.node(),
                    traversal.text() + " в методе '" + method.getNameAsString() + "'" + CONSEQUENCE)));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    private record Traversal(Node node, String text) {
    }

    private Optional<Traversal> firstTraversal(MethodDeclaration method) {
        // Цикл по страницам: двигает номер страницы, смещение или курсор и на каждом шаге читает из репозитория
        for (Statement loop : method.findAll(Statement.class)) {
            boolean paging = (loop instanceof ForStmt || loop instanceof WhileStmt || loop instanceof DoStmt)
                    && CodeContexts.isPagingLoop(loop)
                    && loop.findAll(MethodCallExpr.class).stream().anyMatch(Repositories::isRepositoryCall);
            if (paging) {
                return Optional.of(new Traversal(loop, "Постраничный обход таблицы"));
            }
        }
        // Поток записей из репозитория: строки читаются по мере обхода, но сущности копятся в контексте
        return method.findAll(MethodCallExpr.class).stream()
                .filter(call -> STREAMING_READ.matcher(call.getNameAsString()).matches())
                .filter(Repositories::isRepositoryCall)
                .findFirst()
                .map(call -> new Traversal(call, "Потоковое чтение '" + Nodes.text(call) + "'"));
    }

    private boolean releasesContext(MethodDeclaration method) {
        return method.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> RELEASES.contains(call.getNameAsString()) && call.getScope().isPresent());
    }
}
