package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.InMemoryFiles;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Messaging;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Файл целиком уходит телом сообщения в очередь. У брокера есть предел размера сообщения, а файл
 * при этом держат в памяти сразу отправитель, брокер и получатель. Принятое решение - Claim Check:
 * файл кладут в хранилище, а в сообщении передают ссылку на него.
 */
@Component
public class FileInMessageBodyRule extends AbstractRule {
    private static final String MESSAGE_BUILDER = "MessageBuilder";
    private static final Set<String> BUILDER_METHODS = Set.of("withBody", "withPayload");
    private static final Set<String> MESSAGE_TYPES = Set.of("Message", "ProducerRecord", "GenericMessage");

    private static final String TO_BYTE_ARRAY = "toByteArray";
    private static final String GET_BYTES = "getBytes";
    private static final String READ_ALL_BYTES = "readAllBytes";
    private static final Set<String> UPLOAD_TYPES = Set.of("MultipartFile", "Part");

    // Объект, превращенный в байты: это обычное содержимое сообщения, а не файл
    private static final Set<String> SERIALIZERS =
            Set.of("writeValueAsBytes", "writeValueAsString", "serialize", "encode", "toJson");

    @Override
    public String code() {
        return RuleCodes.FILE_IN_MESSAGE_BODY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет файлы, которые целиком отправляются телом сообщения в очередь";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            boolean builds = BUILDER_METHODS.contains(call.getNameAsString())
                    && call.getScope().filter(scope -> scope.toString().contains(MESSAGE_BUILDER)).isPresent();
            if (builds || Messaging.describeSend(call).isPresent()) {
                report(sourceFile, call.getName(), call.getArguments(), violations);
            }
        }
        for (ObjectCreationExpr creation : sourceFile.unit().findAll(ObjectCreationExpr.class)) {
            if (MESSAGE_TYPES.contains(creation.getType().getNameAsString())) {
                report(sourceFile, creation, creation.getArguments(), violations);
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    private void report(SourceFile sourceFile, Node site, List<Expression> arguments, List<Violation> violations) {
        if (TestClasses.isInside(site)) {
            return;
        }
        arguments.stream().filter(this::isFile).findFirst().ifPresent(file -> violations.add(violation(sourceFile, site,
                "В сообщение целиком кладется файл '" + Nodes.text(file) + "': брокер ограничивает размер"
                        + " сообщения, а файл держат в памяти и отправитель, и брокер, и получатель; сохраните"
                        + " файл в хранилище (S3, общий диск) и отправьте в сообщении ссылку на него"
                        + " (паттерн Claim Check)")));
    }

    private boolean isFile(Expression argument) {
        Expression value = Nodes.unwrap(argument);
        Expression origin = value.isNameExpr() ? LocalTypes.findInitializer(value).map(Nodes::unwrap).orElse(value) : value;
        if (!InMemoryFiles.isByteArray(value) && !producesBytes(value) || isSerialized(origin)) {
            return false;
        }
        return InMemoryFiles.hasFileWord(name(value)) || isFileContent(origin);
    }

    private boolean producesBytes(Expression value) {
        return value.isMethodCallExpr()
                && Set.of(TO_BYTE_ARRAY, GET_BYTES, READ_ALL_BYTES).contains(value.asMethodCallExpr().getNameAsString());
    }

    // json.getBytes(UTF_8), objectMapper.writeValueAsBytes(event), event.toByteArray() у protobuf
    private boolean isSerialized(Expression origin) {
        if (!origin.isMethodCallExpr()) {
            return false;
        }
        MethodCallExpr call = origin.asMethodCallExpr();
        Optional<Expression> scope = call.getScope();
        if (SERIALIZERS.contains(call.getNameAsString())) {
            return true;
        }
        if (GET_BYTES.equals(call.getNameAsString())) {
            return scope.flatMap(LocalTypes::typeOf).filter(UPLOAD_TYPES::contains).isEmpty();
        }
        return TO_BYTE_ARRAY.equals(call.getNameAsString()) && scope.filter(InMemoryFiles::isByteBuffer).isEmpty();
    }

    // Содержимое, которое точно взято из файла или собрано как файл
    private boolean isFileContent(Expression origin) {
        if (!origin.isMethodCallExpr()) {
            return false;
        }
        MethodCallExpr call = origin.asMethodCallExpr();
        return Set.of(TO_BYTE_ARRAY, GET_BYTES, READ_ALL_BYTES).contains(call.getNameAsString())
                || InMemoryFiles.hasFileWord(call.getNameAsString());
    }

    private static String name(Expression value) {
        if (value.isMethodCallExpr()) {
            MethodCallExpr call = value.asMethodCallExpr();
            return call.getNameAsString() + call.getScope().map(MethodCalls::receiverName).orElse("");
        }
        return value.toString();
    }
}
