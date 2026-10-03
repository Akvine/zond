package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.MethodDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;

@Component
public class CheckTransactionOnPrivateMethodRule implements Rule {

    @Override
    public String name() {
        return getClass().getSimpleName();
    }

    @Override
    public String code() {
        return RuleCodes.CHECK_TRANSACTION_ON_PRIVATE_METHOD_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет @Transactional над приватными методами";
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodDeclaration.class).stream()
                .filter(MethodDeclaration::isPrivate)
                .filter(TransactionalAnnotations::isPresent)
                .map(method -> new Violation(
                        errorLevel(),
                        errorType(),
                        code(),
                        name(),
                        sourceFile.path(),
                        method.getBegin().map(position -> position.line).orElse(0),
                        "@Transactional над приватным методом '" + method.getNameAsString()
                                + "' не работает: Spring-прокси не перехватывает приватные методы"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }
}
