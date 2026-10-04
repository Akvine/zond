package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;

@Component
public class CheckRequiresNewInLoopRule extends AbstractRule implements ProjectRule {
    private static final String REQUIRES_NEW = "REQUIRES_NEW";

    @Override
    public String code() {
        return RuleCodes.CHECK_REQUIRES_NEW_IN_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет вызовы методов с Propagation.REQUIRES_NEW в цикле";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        CallGraph graph = CallGraph.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
                if (Loops.isRepeated(call) && graph.targetsOf(call).stream().anyMatch(this::opensNewTransaction)) {
                    violations.add(violation(sourceFile, call,
                            "Вызов '" + call.getNameAsString() + "(...)' с REQUIRES_NEW в цикле: на каждый элемент"
                                    + " открывается отдельная транзакция и берется еще одно соединение из пула;"
                                    + " обрабатывайте элементы пачками в одной транзакции"));
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
        return ErrorType.PERFORMANCE;
    }

    private boolean opensNewTransaction(MethodDeclaration method) {
        return TransactionalAnnotations.find(method)
                .flatMap(TransactionalAnnotations::findOwnBehaviorPropagation)
                .filter(REQUIRES_NEW::equals)
                .isPresent();
    }
}
