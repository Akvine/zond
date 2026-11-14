package ru.akvine.zond.rules.exceptions;

import com.github.javaparser.ast.stmt.CatchClause;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.List;
import java.util.Set;

@Component
public class EmptyCatchRule extends AbstractRule {
    // Имя переменной прямо говорит, что исключение пропускают намеренно
    private static final Set<String> BAD_INPUT = Set.of("NumberFormatException", "DateTimeParseException");
    private static final Set<String> IGNORED_NAMES = Set.of("ignored", "ignore", "expected");
    // Об этом catch точнее скажет правило о проглоченном прерывании: вторая находка на той же строке лишняя
    private static final String INTERRUPTED = "InterruptedException";
    // Так хранилище сообщает, что записи нет: пустой catch оставляет значение по умолчанию
    private static final Set<String> NOT_FOUND = Set.of(
            "NoResultException", "EmptyResultDataAccessException", "NoSuchElementException", "FileNotFoundException",
            "NoSuchFileException", "NoSuchBeanDefinitionException");

    @Override
    public String code() {
        return RuleCodes.EMPTY_CATCH_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет пустые блоки catch";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // Комментарий внутри блока считаем объяснением, почему исключение пропущено
        return sourceFile.unit().findAll(CatchClause.class).stream()
                .filter(clause -> clause.getBody().getStatements().isEmpty())
                .filter(clause -> clause.getBody().getAllContainedComments().isEmpty())
                .filter(clause -> !IGNORED_NAMES.contains(clause.getParameter().getNameAsString()))
                // Long.parseLong(header) в try с пустым catch (NumberFormatException): неразобранное значение
                // пропускают и оставляют значение по умолчанию
                .filter(clause -> !BAD_INPUT.contains(clause.getParameter().getType().asString()))
                .filter(clause -> !INTERRUPTED.equals(simpleName(clause)))
                .map(clause -> {
                    Violation violation = violation(sourceFile, clause,
                            "Пустой catch (" + clause.getParameter().getType() + "): исключение проглатывается,"
                                    + " ошибка останется незамеченной; обработайте его или хотя бы запишите в лог");
                    // catch (NoResultException e) {}: «не найдено» - ожидаемый ответ, а не сбой
                    return NOT_FOUND.contains(simpleName(clause))
                            ? violation.withConfidence(Confidence.SUSPICION)
                            : violation;
                })
                .toList();
    }

    // javax.persistence.NoResultException -> NoResultException
    private String simpleName(CatchClause clause) {
        String type = clause.getParameter().getType().asString();
        return type.substring(type.lastIndexOf('.') + 1);
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.EXCEPTION;
    }
}
