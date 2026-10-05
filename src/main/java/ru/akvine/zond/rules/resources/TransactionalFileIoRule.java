package ru.akvine.zond.rules.resources;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.rules.AbstractTransactionalBlockingCallRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Types;

import java.util.Optional;
import java.util.Set;

@Component
public class TransactionalFileIoRule extends AbstractTransactionalBlockingCallRule {
    // Файловые операции узнаем по известным классам и методам; где тип удается разрешить - проверяем и его
    private static final Set<String> FILE_UTILITIES = Set.of("Files", "FileUtils", "FileCopyUtils", "ImageIO");
    private static final Set<String> FILE_TYPES = Set.of(
            "FileInputStream", "FileOutputStream", "FileReader", "FileWriter", "RandomAccessFile");

    // MultipartFile.transferTo(...)
    private static final Set<String> FILE_METHODS = Set.of("transferTo");

    // У этих типов transferTo пишет на диск; у InputStream и Reader - копирует поток в памяти
    private static final Set<String> TRANSFER_TYPES = Set.of("MultipartFile", "Part", "FileChannel");

    @Override
    public String code() {
        return RuleCodes.TRANSACTIONAL_FILE_IO_RULE_CODE;
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
        if (node instanceof ObjectCreationExpr creation && isFileType(creation)) {
            return Optional.of("new " + creation.getType().getNameAsString());
        }

        if (node instanceof MethodCallExpr call && call.getScope().isPresent()) {
            String receiver = MethodCalls.receiverName(call.getScope().get());
            if (isFileUtility(call, receiver) || isFileTransfer(call)) {
                return Optional.of(receiver + "." + call.getNameAsString());
            }
        }
        return Optional.empty();
    }

    // Сам класс либо его наследник; собственный класс проекта с таким же именем файлом не считается
    private boolean isFileType(ObjectCreationExpr creation) {
        return Types.isKindOf(creation.getType(), FILE_TYPES)
                .orElseGet(() -> FILE_TYPES.contains(creation.getType().getNameAsString()));
    }

    private boolean isFileUtility(MethodCallExpr call, String receiver) {
        return FILE_UTILITIES.contains(receiver) && !Types.isDeclaredInProject(call);
    }

    private boolean isFileTransfer(MethodCallExpr call) {
        return FILE_METHODS.contains(call.getNameAsString())
                && Types.isKindOf(call.getScope().get(), TRANSFER_TYPES).orElse(true);
    }

    @Override
    protected String message(String method, String call) {
        return "Файловая операция '" + call + "' внутри @Transactional-метода '" + method + "': транзакция"
                + " и соединение с БД удерживаются все время работы с диском, а откат транзакции файл не вернет;"
                + " вынесите работу с файлами за пределы транзакции";
    }
}
