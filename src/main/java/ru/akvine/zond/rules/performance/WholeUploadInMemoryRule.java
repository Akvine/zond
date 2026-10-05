package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.List;
import java.util.Set;

@Component
public class WholeUploadInMemoryRule extends AbstractRule {
    private static final String GET_BYTES = "getBytes";
    private static final String READ_ALL_BYTES = "readAllBytes";
    private static final Set<String> UPLOAD_TYPES = Set.of("MultipartFile", "Part");

    // request.getInputStream().readAllBytes(), file.getInputStream().readAllBytes()
    private static final Set<String> STREAM_SOURCES = Set.of("getInputStream", "getBody");

    @Override
    public String code() {
        return RuleCodes.WHOLE_UPLOAD_IN_MEMORY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет чтение загруженного файла или тела запроса в память целиком";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getArguments().isEmpty() && call.getScope().isPresent())
                .filter(call -> readsUpload(call, Nodes.unwrap(call.getScope().get())))
                .filter(call -> !TestClasses.isInside(call))
                .map(call -> violation(sourceFile, call,
                        "'" + call + "' читает загруженные данные в память целиком: несколько больших файлов"
                                + " одновременно исчерпают память; обрабатывайте поток по частям"
                                + " (getInputStream(), transferTo(...))"))
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

    private boolean readsUpload(MethodCallExpr call, Expression scope) {
        if (GET_BYTES.equals(call.getNameAsString())) {
            return LocalTypes.typeOf(scope).filter(UPLOAD_TYPES::contains).isPresent();
        }
        return READ_ALL_BYTES.equals(call.getNameAsString())
                && scope.isMethodCallExpr()
                && STREAM_SOURCES.contains(scope.asMethodCallExpr().getNameAsString());
    }
}
