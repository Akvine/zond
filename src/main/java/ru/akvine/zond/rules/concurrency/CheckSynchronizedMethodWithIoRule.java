package ru.akvine.zond.rules.concurrency;

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
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.SpringBeans;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public class CheckSynchronizedMethodWithIoRule extends AbstractRule {
    private static final Pattern NETWORK_RECEIVER = Pattern.compile(
            ".*(resttemplate|webclient|restclient|httpclient|jdbctemplate|feign).*", Pattern.CASE_INSENSITIVE);

    @Override
    public String code() {
        return RuleCodes.CHECK_SYNCHRONIZED_METHOD_WITH_IO_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет synchronized-методы бинов с обращением к БД или по сети внутри";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (!SpringBeans.isBean(type)) {
                continue;
            }
            for (MethodDeclaration method : type.getMethods()) {
                if (!method.isSynchronized()) {
                    continue;
                }
                findSlowCall(method).ifPresent(call -> violations.add(violation(sourceFile, method,
                        "synchronized-метод '" + method.getNameAsString() + "' бина ждет '" + call + "':"
                                + " бин один на все запросы, и пока идет обращение, остальные потоки стоят"
                                + " в очереди; сократите блокировку до работы с общим состоянием")));
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
        return ErrorType.CONCURRENCY;
    }

    private Optional<String> findSlowCall(MethodDeclaration method) {
        return method.findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getScope().isPresent())
                .filter(call -> Repositories.isRepositoryCall(call)
                        || NETWORK_RECEIVER.matcher(MethodCalls.receiverName(call.getScope().get())).matches())
                .map(call -> call.getScope().get() + "." + call.getNameAsString())
                .findFirst();
    }
}
