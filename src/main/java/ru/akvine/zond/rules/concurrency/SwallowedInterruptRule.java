package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

@Component
public class SwallowedInterruptRule extends AbstractRule {
    private static final String INTERRUPTED_EXCEPTION = "InterruptedException";
    private static final String INTERRUPT = "interrupt";
    // Тело потока: после выхода из него флаг прерывания проверять уже некому
    private static final Set<String> THREAD_BODIES = Set.of("run", "main");

    @Override
    public String code() {
        return RuleCodes.SWALLOWED_INTERRUPT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет перехват InterruptedException без восстановления флага прерывания";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(CatchClause.class).stream()
                .filter(this::catchesInterrupt)
                .filter(clause -> !restoresInterrupt(clause))
                .filter(clause -> !stopsThread(clause))
                .map(clause -> violation(sourceFile, clause,
                        "InterruptedException перехвачен, а флаг прерывания не восстановлен: поток не узнает, что"
                                + " его просили остановиться, и пул не сможет завершить задачу; вызовите"
                                + " Thread.currentThread().interrupt() либо пробросьте исключение"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CONCURRENCY;
    }

    // catch (InterruptedException e) либо catch (InterruptedException | ExecutionException e)
    private boolean catchesInterrupt(CatchClause clause) {
        Type type = clause.getParameter().getType();
        Stream<Type> types = type.isUnionType()
                ? type.asUnionType().getElements().stream().map(element -> (Type) element)
                : Stream.of(type);
        return types.map(LocalTypes::typeName).anyMatch(INTERRUPTED_EXCEPTION::equals);
    }

    /**
     * Поток заканчивает работу в ответ на прерывание: catch в run() или main() выходит из цикла или метода
     * ({@code catch (InterruptedException e) { break; }}), либо try - последний оператор метода. Это и есть
     * реакция на просьбу остановиться
     */
    private boolean stopsThread(CatchClause clause) {
        Optional<MethodDeclaration> body = Nodes.enclosingCallable(clause)
                .filter(callable -> callable instanceof MethodDeclaration)
                .map(callable -> (MethodDeclaration) callable)
                .filter(method -> THREAD_BODIES.contains(method.getNameAsString()))
                .filter(method -> !Nodes.isInNestedScope(clause, method));
        if (body.isEmpty()) {
            return false;
        }
        List<Statement> handling = clause.getBody().getStatements();
        if (!handling.isEmpty() && (handling.get(handling.size() - 1).isBreakStmt()
                || handling.get(handling.size() - 1).isReturnStmt())) {
            return true;
        }
        Optional<Node> tryStatement = clause.getParentNode().filter(parent -> parent instanceof TryStmt);
        return tryStatement.isPresent() && body.get().getBody()
                .map(BlockStmt::getStatements)
                .filter(statements -> !statements.isEmpty() && statements.get(statements.size() - 1) == tryStatement.get())
                .isPresent();
    }

    // Флаг восстановлен либо исключение пробрасывается дальше
    private boolean restoresInterrupt(CatchClause clause) {
        boolean interrupts = clause.getBody().findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> INTERRUPT.equals(call.getNameAsString()));
        return interrupts || clause.getBody().findAll(ThrowStmt.class).stream()
                .anyMatch(throwStmt -> !Nodes.isInNestedScope(throwStmt, clause.getBody()))
                || remembersInterrupt(clause);
    }

    /**
     * catch (InterruptedException e) { interrupted = true; } ... finally { if (interrupted) Thread.currentThread()
     * .interrupt(); }: прерывание запомнили, чтобы восстановить, когда ожидание закончится
     */
    private boolean remembersInterrupt(CatchClause clause) {
        boolean remembers = clause.getBody().findAll(AssignExpr.class).stream()
                .anyMatch(assignment -> assignment.getTarget().isNameExpr()
                        && assignment.getValue().isBooleanLiteralExpr()
                        && assignment.getValue().asBooleanLiteralExpr().getValue());
        return remembers && Nodes.enclosingCallable(clause)
                .filter(callable -> callable.findAll(MethodCallExpr.class).stream()
                        .anyMatch(call -> INTERRUPT.equals(call.getNameAsString())))
                .isPresent();
    }
}
