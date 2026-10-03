package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class CheckThreadLocalNotRemovedRule extends AbstractRule {
    private static final String SET = "set";
    private static final String REMOVE = "remove";
    private static final Set<String> THREAD_LOCAL_TYPES = Set.of("ThreadLocal", "InheritableThreadLocal");

    @Override
    public String code() {
        return RuleCodes.CHECK_THREAD_LOCAL_NOT_REMOVED_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет ThreadLocal, в который пишут, но который нигде не очищают";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<MethodCallExpr> calls = sourceFile.unit().findAll(MethodCallExpr.class);
        List<Violation> violations = new ArrayList<>();

        for (FieldDeclaration field : sourceFile.unit().findAll(FieldDeclaration.class)) {
            for (VariableDeclarator variable : field.getVariables()) {
                if (!THREAD_LOCAL_TYPES.contains(LocalTypes.typeName(variable.getType()))) {
                    continue;
                }

                String name = variable.getNameAsString();
                if (isCalled(calls, name, SET) && !isCalled(calls, name, REMOVE)) {
                    violations.add(violation(sourceFile, variable,
                            "ThreadLocal '" + name + "' заполняется через set(...), но нигде не очищается: в пуле"
                                    + " потоков значение останется в потоке и попадет в следующую задачу;"
                                    + " вызывайте remove() в finally"));
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
        return ErrorType.RESOURCE;
    }

    // CONTEXT.set(...), this.context.set(...), Holder.CONTEXT.set(...)
    private boolean isCalled(List<MethodCallExpr> calls, String threadLocal, String method) {
        return calls.stream()
                .filter(call -> method.equals(call.getNameAsString()))
                .anyMatch(call -> call.getScope()
                        .filter(scope -> MethodCalls.receiverName(scope).equals(threadLocal))
                        .isPresent());
    }
}
