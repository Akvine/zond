package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.TypeDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.ArrayList;
import java.util.List;

@Component
public class FileNameMismatchRule extends AbstractRule {
    private static final String JAVA_EXTENSION = ".java";

    @Override
    public String code() {
        return RuleCodes.FILE_NAME_MISMATCH_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет public-типы, имя которых не совпадает с именем файла";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        String fileName = sourceFile.path().getFileName().toString();
        if (!fileName.endsWith(JAVA_EXTENSION)) {
            return List.of();
        }
        String expected = fileName.substring(0, fileName.length() - JAVA_EXTENSION.length());

        // Проверяем только типы верхнего уровня: вложенные public-классы называются как угодно
        List<Violation> violations = new ArrayList<>();
        for (TypeDeclaration<?> type : sourceFile.unit().getTypes()) {
            if (type.isPublic() && !type.getNameAsString().equals(expected)) {
                violations.add(violation(sourceFile, type,
                        "public-тип '" + type.getNameAsString() + "' объявлен в файле '" + fileName + "':"
                                + " такой код не компилируется; назовите файл по имени типа"));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.BLOCKER;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }
}
