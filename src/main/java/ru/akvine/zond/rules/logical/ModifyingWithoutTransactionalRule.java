package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.TransactionalAnnotations;

import java.util.List;

@Component
public class ModifyingWithoutTransactionalRule extends AbstractRule {
    private static final String MODIFYING = "Modifying";

    @Override
    public String code() {
        return RuleCodes.MODIFYING_WITHOUT_TRANSACTIONAL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет @Modifying-запросы репозитория без @Transactional";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodDeclaration.class).stream()
                .filter(method -> Annotations.has(method, MODIFYING))
                .filter(method -> !isTransactional(method))
                .map(method -> violation(sourceFile, method,
                        "@Modifying-запрос '" + method.getNameAsString() + "' без @Transactional: свои методы"
                                + " репозитория Spring Data в транзакцию не оборачивает, при вызове вне транзакции"
                                + " будет TransactionRequiredException; добавьте @Transactional на метод"
                                + " или на репозиторий"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    // Аннотация на методе либо на самом репозитории
    private boolean isTransactional(MethodDeclaration method) {
        return TransactionalAnnotations.isPresent(method)
                || method.getParentNode()
                .filter(parent -> parent instanceof TypeDeclaration<?> type && TransactionalAnnotations.isPresent(type))
                .isPresent();
    }
}
