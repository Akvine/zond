package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;

import java.util.Optional;
import java.util.Set;

@Component
public class CheckTransactionalFileIoRule extends AbstractTransactionalBlockingCallRule {
    // Типы не разрешаем, поэтому файловые операции узнаем по известным классам и методам
    private static final Set<String> FILE_UTILITIES = Set.of("Files", "FileUtils", "FileCopyUtils", "ImageIO");
    private static final Set<String> FILE_TYPES = Set.of(
            "FileInputStream", "FileOutputStream", "FileReader", "FileWriter", "RandomAccessFile");

    // MultipartFile.transferTo(...)
    private static final Set<String> FILE_METHODS = Set.of("transferTo");

    @Override
    public String code() {
        return RuleCodes.CHECK_TRANSACTIONAL_FILE_IO_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет файловые операции внутри @Transactional-методов";
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.RESOURCE;
    }

    @Override
    protected Optional<String> describeBlockingCall(Node node) {
        if (node instanceof ObjectCreationExpr creation
                && FILE_TYPES.contains(creation.getType().getNameAsString())) {
            return Optional.of("new " + creation.getType().getNameAsString());
        }

        if (node instanceof MethodCallExpr call && call.getScope().isPresent()) {
            String receiver = MethodCalls.receiverName(call.getScope().get());
            if (FILE_UTILITIES.contains(receiver) || FILE_METHODS.contains(call.getNameAsString())) {
                return Optional.of(receiver + "." + call.getNameAsString());
            }
        }
        return Optional.empty();
    }

    @Override
    protected String message(String method, String call) {
        return "Файловая операция '" + call + "' внутри @Transactional-метода '" + method + "': транзакция"
                + " и соединение с БД удерживаются все время работы с диском, а откат транзакции файл не вернет;"
                + " вынесите работу с файлами за пределы транзакции";
    }
}
