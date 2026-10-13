package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Handlers;
import ru.akvine.zond.rules.support.InMemoryFiles;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Ответ другого сервиса или объект из хранилища читается в память целиком: {@code byte[].class}
 * типом ответа, {@code BodyHandlers.ofByteArray()}, {@code getObjectAsBytes}. Для файла это весь его
 * размер на каждый запрос.
 */
@Component
public class RemoteFileIntoMemoryRule extends AbstractRule {
    // restTemplate.getForObject(url, byte[].class), webClient...bodyToMono(byte[].class)
    private static final Set<String> TYPED_RESPONSES = Set.of(
            "getForObject", "getForEntity", "postForObject", "postForEntity", "patchForObject", "exchange",
            "body", "toEntity", "bodyToMono", "bodyToFlux");
    private static final String OF_BYTE_ARRAY = "ofByteArray";
    private static final String BODY_HANDLERS = "BodyHandlers";
    // okhttp: response.body().bytes()
    private static final String BYTES = "bytes";
    private static final String BODY = "body";
    // AWS SDK: s3.getObjectAsBytes(request), ResponseTransformer.toBytes()
    private static final String GET_OBJECT_AS_BYTES = "getObjectAsBytes";
    private static final String TO_BYTES = "toBytes";
    private static final String RESPONSE_TRANSFORMER = "ResponseTransformer";

    private static final Pattern SMALL =
            Pattern.compile("\\bqr|QR|Qr(?=[A-Z])|(?i:barcode|captcha|thumb|avatar|icon|logo)");

    private static final Pattern BYTES_RETURN =
            Pattern.compile("^(byte\\[]|(ResponseEntity|HttpEntity|Mono)<byte\\[]>)$");

    @Override
    public String code() {
        return RuleCodes.REMOTE_FILE_INTO_MEMORY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет чтение ответа другого сервиса или объекта из хранилища в массив байт целиком";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (readsWholeResponse(call) && !TestClasses.isInside(call) && !isSmall(call)) {
                violations.add(violation(sourceFile, call.getName(), message("'" + Nodes.text(call) + "' читает")));
            }
        }
        // Клиент, описанный интерфейсом: тип ответа задан типом метода
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (!Handlers.isClient(type) || TestClasses.isInside(type)) {
                continue;
            }
            for (MethodDeclaration method : type.getMethods()) {
                if (BYTES_RETURN.matcher(method.getType().asString()).matches()) {
                    violations.add(violation(sourceFile, method.getName(),
                            message("Метод клиента '" + method.getNameAsString() + "' возвращает "
                                    + method.getType().asString() + " и тем самым читает")));
                }
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

    private boolean readsWholeResponse(MethodCallExpr call) {
        String name = call.getNameAsString();
        if (TYPED_RESPONSES.contains(name)) {
            return call.getArguments().stream().anyMatch(this::isByteArrayClass);
        }
        String scope = call.getScope().map(Node::toString).orElse("");
        if (OF_BYTE_ARRAY.equals(name)) {
            return scope.endsWith(BODY_HANDLERS);
        }
        if (TO_BYTES.equals(name)) {
            return scope.endsWith(RESPONSE_TRANSFORMER);
        }
        if (BYTES.equals(name) && call.getArguments().isEmpty()) {
            return call.getScope()
                    .map(Nodes::unwrap)
                    .filter(Expression::isMethodCallExpr)
                    .filter(body -> BODY.equals(body.asMethodCallExpr().getNameAsString()))
                    .isPresent();
        }
        return GET_OBJECT_AS_BYTES.equals(name);
    }

    // QR-код, капча, значок: такие ответы малы, и читать их целиком нормально
    private boolean isSmall(MethodCallExpr call) {
        return SMALL.matcher(call.toString()).find()
                || call.findAncestor(MethodDeclaration.class)
                .filter(method -> SMALL.matcher(method.getNameAsString()).find())
                .isPresent()
                || call.findAncestor(ClassOrInterfaceDeclaration.class)
                .filter(type -> SMALL.matcher(type.getNameAsString()).find())
                .isPresent();
    }

    private boolean isByteArrayClass(Expression argument) {
        Expression value = Nodes.unwrap(argument);
        return value instanceof ClassExpr type && InMemoryFiles.BYTE_ARRAY.equals(type.getType().asString());
    }

    private static String message(String subject) {
        return subject + " ответ в память целиком: для файла это весь его размер на каждый запрос, а"
                + " несколько больших файлов одновременно память исчерпают; читайте поток по частям и сразу"
                + " пишите его дальше (RestTemplate.execute с ResponseExtractor, bodyToFlux(DataBuffer.class)"
                + " с DataBufferUtils.write, BodyHandlers.ofInputStream или ofFile)";
    }
}
