package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.List;

@Component
public class InsecureTempFileRule extends AbstractRule {
    private static final String FILE = "File";
    private static final String CREATE_TEMP_FILE = "createTempFile";

    @Override
    public String code() {
        return RuleCodes.INSECURE_TEMP_FILE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет временные файлы, созданные через File.createTempFile";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> MethodCalls.isCallOn(call, FILE, CREATE_TEMP_FILE) && !TestClasses.isInside(call))
                .map(call -> violation(sourceFile, call,
                        "File.createTempFile(...) создает файл с правами по умолчанию: в общем каталоге /tmp"
                                + " его могут прочитать другие пользователи системы; используйте"
                                + " Files.createTempFile(...) - он выдает доступ только владельцу"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }
}
