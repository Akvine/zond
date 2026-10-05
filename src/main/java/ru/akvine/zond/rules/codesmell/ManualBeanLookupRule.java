package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class ManualBeanLookupRule extends AbstractRule {
    private static final String GET_BEAN = "getBean";
    private static final Set<String> CONFIGURATION_ANNOTATIONS = Set.of("Configuration", "TestConfiguration");

    // applicationContext.getBean(...), beanFactory.getBean(...), ctx.getBean(...)
    private static final Pattern CONTEXT_NAME = Pattern.compile(".*(context|beanfactory)$|^ctx$", Pattern.CASE_INSENSITIVE);

    @Override
    public String code() {
        return RuleCodes.MANUAL_BEAN_LOOKUP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет получение бинов вручную через ApplicationContext.getBean()";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> GET_BEAN.equals(call.getNameAsString()))
                .filter(call -> call.getScope()
                        .map(MethodCalls::receiverName)
                        .filter(receiver -> CONTEXT_NAME.matcher(receiver).matches())
                        .isPresent())
                .filter(call -> !isInsideConfiguration(call) && !TestClasses.isInside(call))
                .map(call -> violation(sourceFile, call,
                        "'" + call + "': бин берется из контекста вручную - зависимость класса не видна в его"
                                + " конструкторе, а ошибка обнаружится только при вызове, а не при старте;"
                                + " внедрите зависимость через конструктор"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    // В классах конфигурации работа с контекстом напрямую - обычное дело
    private boolean isInsideConfiguration(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof TypeDeclaration<?> type && Annotations.hasAny(type, CONFIGURATION_ANNOTATIONS)) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }
}
