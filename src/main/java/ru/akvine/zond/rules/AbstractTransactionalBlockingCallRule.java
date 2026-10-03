package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Медленная внешняя операция внутри транзакции: пока она идет, транзакция держит соединение с БД.
 */
public abstract class AbstractTransactionalBlockingCallRule extends AbstractRule {

    /**
     * @return описание операции (например, restTemplate.postForObject), если узел - блокирующий вызов
     */
    protected abstract Optional<String> describeBlockingCall(Node node);

    /**
     * @return текст нарушения для операции, найденной в транзакционном методе
     */
    protected abstract String message(String method, String call);

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            // На приватных методах @Transactional не работает в принципе, это ловит отдельное правило
            if (method.isPrivate()
                    || method.getBody().isEmpty()
                    || TransactionalAnnotations.findEffective(method).isEmpty()) {
                continue;
            }

            // webClient.get().uri(...).retrieve() - одна операция, а не три
            Set<Integer> reportedLines = new HashSet<>();
            for (Node node : method.getBody().get().findAll(Node.class)) {
                Optional<String> call = describeBlockingCall(node);
                int line = node.getBegin().map(position -> position.line).orElse(0);
                if (call.isPresent() && reportedLines.add(line)) {
                    violations.add(violation(sourceFile, node, message(method.getNameAsString(), call.get())));
                }
            }
        }
        return violations;
    }
}
