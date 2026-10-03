package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.stream.Stream;

@Component
public class CheckSwallowedInterruptRule extends AbstractRule {
    private static final String INTERRUPTED_EXCEPTION = "InterruptedException";
    private static final String INTERRUPT = "interrupt";

    @Override
    public String code() {
        return RuleCodes.CHECK_SWALLOWED_INTERRUPT_RULE_CODE;
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

    // Флаг восстановлен либо исключение пробрасывается дальше
    private boolean restoresInterrupt(CatchClause clause) {
        boolean interrupts = clause.getBody().findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> INTERRUPT.equals(call.getNameAsString()));
        return interrupts || clause.getBody().findAll(ThrowStmt.class).stream()
                .anyMatch(throwStmt -> !Nodes.isInNestedScope(throwStmt, clause.getBody()));
    }
}
