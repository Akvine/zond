package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;

@Component
public class CheckRepositoryCallInLoopRule extends AbstractRule {

    @Override
    public String code() {
        return RuleCodes.CHECK_REPOSITORY_CALL_IN_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет обращения к репозиторию в цикле и в поэлементных операциях стримов";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getScope().map(MethodCalls::receiverName).filter(Repositories::isRepository).isPresent())
                .filter(Loops::isRepeated)
                .map(call -> violation(sourceFile, call,
                        "Обращение к репозиторию '" + call.getScope().get() + "." + call.getNameAsString()
                                + "' в цикле: на каждый элемент уходит отдельный запрос к БД (проблема N+1);"
                                + " загрузите или сохраните данные одним запросом: findAllById, saveAll,"
                                + " запрос с IN"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }
}
