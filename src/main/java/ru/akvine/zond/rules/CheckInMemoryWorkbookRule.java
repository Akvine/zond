package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Set;

@Component
public class CheckInMemoryWorkbookRule extends AbstractRule {
    // Apache POI: эти книги целиком держат документ в памяти
    private static final Set<String> IN_MEMORY_WORKBOOKS = Set.of("XSSFWorkbook", "HSSFWorkbook");

    @Override
    public String code() {
        return RuleCodes.CHECK_IN_MEMORY_WORKBOOK_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет создание Excel-документа целиком в памяти (XSSFWorkbook)";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // Книга без аргументов создается для записи; с аргументом - это чтение готового файла
        return sourceFile.unit().findAll(ObjectCreationExpr.class).stream()
                .filter(creation -> IN_MEMORY_WORKBOOKS.contains(creation.getType().getNameAsString()))
                .filter(creation -> creation.getArguments().isEmpty())
                .map(creation -> violation(sourceFile, creation,
                        "'" + creation + "' собирает документ целиком в памяти: на больших выгрузках это"
                                + " OutOfMemoryError; используйте потоковый SXSSFWorkbook, который сбрасывает"
                                + " строки на диск"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }
}
