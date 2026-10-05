package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.SpringBeans;

import java.util.List;

@Component
public class ManualThreadInBeanRule extends AbstractRule {
    private static final String THREAD = "Thread";

    @Override
    public String code() {
        return RuleCodes.MANUAL_THREAD_IN_BEAN_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет создание потоков вручную внутри Spring-бинов";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(ObjectCreationExpr.class).stream()
                .filter(creation -> THREAD.equals(creation.getType().getNameAsString()))
                .filter(this::isInsideBean)
                .map(creation -> violation(sourceFile, creation,
                        "'new Thread(...)' в Spring-бине: число таких потоков ничем не ограничено, при остановке"
                                + " приложения их никто не завершит, а исключение в потоке останется незамеченным;"
                                + " используйте управляемый пул: TaskExecutor или @Async"))
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

    private boolean isInsideBean(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof ClassOrInterfaceDeclaration type && SpringBeans.isBean(type)) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }
}
