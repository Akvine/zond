package ru.akvine.zond.rules.exceptions;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loggers;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class BroadCatchRule extends AbstractRule {
    private static final Set<String> SHUTDOWN_METHODS =
            Set.of("destroy", "close", "shutdown", "stop", "dispose", "cleanup", "cleanUp", "onShutdown");
    private static final Set<String> RELEASE_METHODS = Set.of(
            "shutdown", "shutdownNow", "close", "stop", "disconnect", "destroy", "release", "unlock", "awaitTermination",
            "interrupt", "cancel");
    private static final String PRE_DESTROY = "PreDestroy";
    // Обработчик ошибки асинхронной цепочки: исключение уходит к тому, кто ждет результат
    private static final Set<String> FAILURE_HANDLERS = Set.of(
            "onFailure", "onError", "tellFailure", "completeExceptionally", "setException", "fail", "failure");
    private static final Set<String> BROAD_TYPES = Set.of("Throwable", "Exception");

    @Override
    public String code() {
        return RuleCodes.BROAD_CATCH_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет catch (Throwable) и catch (Exception) без повторного выброса исключения";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (CatchClause clause : sourceFile.unit().findAll(CatchClause.class)) {
            // Пустой catch ловит отдельное правило
            if (clause.getBody().getStatements().isEmpty()) {
                continue;
            }

            // На границе задачи ловить все и положено: выше исключение обработать некому. Нужно лишь, чтобы оно
            // не пропало - ушло в лог или обработчику вместе со стеком
            boolean barrier = CodeContexts.isTaskBoundary(clause) && CodeContexts.passesExceptionOn(clause);
            Optional<String> broadType = findBroadType(clause.getParameter().getType());
            if (broadType.isPresent() && !rethrows(clause) && !barrier && !isRelease(clause)
                    && !passesToFailureHandler(clause)) {
                violations.add(violation(sourceFile, clause,
                        "catch (" + broadType.get() + ") без повторного выброса: вместе с ожидаемыми ошибками"
                                + " перехватываются и ошибки программирования (NullPointerException и т.п.);"
                                + " ловите конкретные исключения или пробрасывайте дальше"));
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
        return ErrorType.EXCEPTION;
    }

    // catch (Exception e) либо catch (IOException | Exception e)
    private Optional<String> findBroadType(Type type) {
        List<Type> types = type.isUnionType() ? new ArrayList<>(type.asUnionType().getElements()) : List.of(type);
        return types.stream()
                .map(LocalTypes::typeName)
                .filter(BROAD_TYPES::contains)
                .findFirst();
    }

    /**
     * Освобождение ресурса при остановке: destroy() { try { scheduler.shutdownNow(); } catch (Exception e) { ... } }.
     * Сбой здесь уже ничего не испортит, а остановку остальных ресурсов прерывать нельзя
     */
    // msg.getCallback().onFailure(new RuleEngineException(e.getMessage(), e)): сбой передан тому, кто ждет ответ
    private boolean passesToFailureHandler(CatchClause clause) {
        String exception = clause.getParameter().getNameAsString();
        return clause.getBody().findAll(MethodCallExpr.class).stream()
                .filter(call -> FAILURE_HANDLERS.contains(call.getNameAsString()))
                .flatMap(call -> call.getArguments().stream())
                .anyMatch(argument -> argument.findAll(NameExpr.class).stream()
                        .anyMatch(name -> name.getNameAsString().equals(exception)));
    }

    // Освобождение ресурса: при остановке бина либо в любом месте, если сбой закрытия записан в лог со стеком -
    // state.close() не должен ронять остальную работу
    private boolean isRelease(CatchClause clause) {
        Optional<MethodDeclaration> method = clause.findAncestor(MethodDeclaration.class);
        Optional<TryStmt> tryStatement = clause.getParentNode().filter(parent -> parent instanceof TryStmt)
                .map(parent -> (TryStmt) parent);
        if (method.isEmpty() || tryStatement.isEmpty()) {
            return false;
        }
        boolean shutdown = SHUTDOWN_METHODS.contains(method.get().getNameAsString())
                || Annotations.has(method.get(), PRE_DESTROY) || CodeContexts.logsException(clause);
        List<MethodCallExpr> calls = tryStatement.get().getTryBlock().findAll(MethodCallExpr.class);
        return shutdown && !calls.isEmpty() && calls.stream()
                .allMatch(call -> RELEASE_METHODS.contains(call.getNameAsString()) || Loggers.isLogCall(call));
    }

    private boolean rethrows(CatchClause clause) {
        return clause.getBody().findAll(ThrowStmt.class).stream()
                .anyMatch(throwStmt -> !Nodes.isInNestedScope(throwStmt, clause.getBody()));
    }
}
