package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class BeanMethodCallOutsideConfigurationRule extends AbstractRule {
    private static final String BEAN = "Bean";
    private static final String CONFIGURATION = "Configuration";

    // @Configuration(proxyBeanMethods = false) отключает перехват вызовов так же, как @Component
    private static final String PROXY_DISABLED = "proxyBeanMethods = false";

    @Override
    public String code() {
        return RuleCodes.BEAN_METHOD_CALL_OUTSIDE_CONFIGURATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет вызовы @Bean-методов друг из друга в классах без @Configuration";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            Set<String> beanMethods = type.getMethods().stream()
                    .filter(method -> Annotations.has(method, BEAN))
                    .map(MethodDeclaration::getNameAsString)
                    .collect(Collectors.toSet());
            if (beanMethods.isEmpty() || isProxiedConfiguration(type)) {
                continue;
            }
            for (MethodDeclaration method : type.getMethods()) {
                if (!Annotations.has(method, BEAN)) {
                    continue;
                }
                for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                    boolean callsBeanMethod = beanMethods.contains(call.getNameAsString())
                            && call.getScope().filter(scope -> !scope.isThisExpr()).isEmpty();
                    if (callsBeanMethod) {
                        violations.add(violation(sourceFile, call,
                                "@Bean-метод '" + call.getNameAsString() + "' вызван напрямую в классе без"
                                        + " @Configuration: Spring вызов не перехватывает, и создается второй"
                                        + " экземпляр вместо бина из контекста; пометьте класс @Configuration"
                                        + " либо получайте бин параметром метода"));
                    }
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
        return ErrorType.LOGICAL;
    }

    private boolean isProxiedConfiguration(ClassOrInterfaceDeclaration type) {
        return Annotations.find(type, CONFIGURATION)
                .filter(annotation -> !annotation.toString().contains(PROXY_DISABLED))
                .isPresent();
    }
}
