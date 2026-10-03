package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckStreamReuseRule extends AbstractRule {
    private static final Set<String> STREAM_TYPES = Set.of("Stream", "IntStream", "LongStream", "DoubleStream");

    @Override
    public String code() {
        return RuleCodes.CHECK_STREAM_REUSE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет стрим, сохраненный в переменную и использованный больше одного раза";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (VariableDeclarator variable : sourceFile.unit().findAll(VariableDeclarator.class)) {
            Optional<Node> callable = Nodes.enclosingCallable(variable);
            if (callable.isEmpty()
                    || !Resources.isLocalVariable(variable)
                    || !STREAM_TYPES.contains(LocalTypes.typeName(variable.getType()))) {
                continue;
            }

            String name = variable.getNameAsString();
            // Переменной присваивают новый стрим (stream = stream.filter(...)) - это уже не повторное использование
            boolean reassigned = callable.get().findAll(AssignExpr.class).stream()
                    .anyMatch(assign -> assign.getTarget().toString().equals(name));
            List<MethodCallExpr> usages = reassigned ? List.of() : findCallsOn(callable.get(), name);
            if (usages.size() > 1) {
                violations.add(violation(sourceFile, usages.get(1),
                        "Стрим '" + name + "' используется повторно: после первой операции он закрыт, вторая"
                                + " закончится IllegalStateException \"stream has already been operated upon\";"
                                + " создавайте стрим заново либо сохраните результат в коллекцию"));
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
        return ErrorType.STREAM;
    }

    // Вызовы методов прямо на переменной: stream.count(), stream.filter(...)
    private List<MethodCallExpr> findCallsOn(Node callable, String name) {
        return callable.findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getScope()
                        .filter(scope -> scope instanceof NameExpr && scope.toString().equals(name))
                        .isPresent())
                .toList();
    }
}
