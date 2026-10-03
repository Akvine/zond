package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.MethodDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;

@Component
public class CheckTransactionOnPrivateMethodRule implements Rule {
    private static final String TRANSACTIONAL = "Transactional";

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
                .filter(this::isTransactional)
                .map(method -> new Violation(
                        code(),
                        name(),
                        sourceFile.path(),
                        method.getBegin().map(position -> position.line).orElse(0),
                        "@Transactional над приватным методом '" + method.getNameAsString()
                                + "' не работает: Spring-прокси не перехватывает приватные методы"))
                .toList();
    }

    // Сравниваем по простому имени, чтобы поймать и @Transactional, и полное имя (spring / jakarta / javax)
    private boolean isTransactional(MethodDeclaration method) {
        return method.getAnnotations().stream()
                .anyMatch(annotation -> TRANSACTIONAL.equals(annotation.getName().getIdentifier()));
    }
}
