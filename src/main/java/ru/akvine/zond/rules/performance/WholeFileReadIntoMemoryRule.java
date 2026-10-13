package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.InMemoryFiles;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loops;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Файл или поток читается в память целиком. О загруженном файле и теле запроса сообщает
 * {@link WholeUploadInMemoryRule}; здесь - файлы с диска, из хранилища и из любого другого потока.
 */
@Component
public class WholeFileReadIntoMemoryRule extends AbstractRule implements ProjectRule {
    // Статические методы, которые возвращают содержимое целиком
    private static final Map<String, Set<String>> READS = Map.of(
            "Files", Set.of("readAllBytes", "readAllLines", "readString"),
            "FileUtils", Set.of("readFileToByteArray", "readFileToString", "readLines"),
            "IOUtils", Set.of("toByteArray", "toString", "readLines"),
            "StreamUtils", Set.of("copyToByteArray", "copyToString"),
            "FileCopyUtils", Set.of("copyToByteArray", "copyToString"),
            "ByteStreams", Set.of("toByteArray"));
    // Копирование потока, которое вредно только когда приемник - буфер в памяти
    private static final Set<String> COPY_UTILITIES = Set.of("IOUtils", "StreamUtils", "Files", "FileCopyUtils");
    private static final String COPY = "copy";
    private static final String TRANSFER_TO = "transferTo";
    private static final String WRITE = "write";
    private static final int COPY_WRITE_ARGUMENTS = 3;

    private static final String READ_ALL_BYTES = "readAllBytes";
    private static final String CONTENT_AS_BYTES = "getContentAsByteArray";
    private static final Pattern RESOURCE = Pattern.compile("(?i)resource");
    // Об этих источниках уже сообщает jr:242
    private static final Set<String> UPLOAD_SOURCES = Set.of("getInputStream", "getBody");

    // Текст ответа другого сервиса читают целиком, чтобы его разобрать: это не файл
    private static final Set<String> TEXT_READS = Set.of("toString", "copyToString");
    private static final Pattern HTTP_BODY = Pattern.compile("\\.(body|getBody|getErrorStream|getResponseBody)\\(");

    // Файл, размер которого известен заранее: ресурс из сборки, ключ, шаблон, настройки
    private static final Pattern BOUNDED = Pattern.compile(
            "(?i)getResourceAsStream|ClassPathResource|getResource\\(|classpath|ResourceUtils|key|cert|\\bpem\\b"
                    + "|secret|token|template|config|propert|schema|banner|version|licen[sc]e|migration|script"
                    + "|query|\\.sql");
    private static final Set<String> EXTERNAL_TYPES = Set.of("MultipartFile", "Part", "InputStream", "File", "Path");

    // Приложение обслуживает запросы: несколько файлов читаются одновременно, и память складывается
    private static final Set<String> REQUEST_HANDLING = Set.of(
            "RestController", "Controller", "RabbitListener", "KafkaListener", "JmsListener", "SqsListener",
            "Scheduled", "MessageMapping");

    @Override
    public String code() {
        return RuleCodes.WHOLE_FILE_READ_INTO_MEMORY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет чтение файла или потока в память целиком";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        // Именно аннотации: само слово "Scheduled" встречается и в строках, и в названиях классов
        boolean server = sourceFiles.stream()
                .flatMap(sourceFile -> sourceFile.unit().findAll(AnnotationExpr.class).stream())
                .anyMatch(annotation -> REQUEST_HANDLING.contains(annotation.getNameAsString()));
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            check(sourceFile, server, violations);
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

    private void check(SourceFile sourceFile, boolean server, List<Violation> violations) {
        Set<Node> reportedLoops = new HashSet<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (call.getScope().isEmpty() || TestClasses.isInside(call) || CodeContexts.isStartup(call)) {
                continue;
            }
            Optional<String> subject = subject(call, reportedLoops);
            if (subject.isEmpty() || isBounded(call)) {
                continue;
            }
            // Утилита командной строки читает один файл за раз - там это редко бывает бедой
            Confidence confidence = server && isData(call) ? Confidence.PROBABLE : Confidence.SUSPICION;
            violations.add(violation(sourceFile, call.getName(),
                    subject.get() + ": память равна размеру содержимого, и несколько больших файлов одновременно"
                            + " ее исчерпают; читайте по частям (Files.newBufferedReader, Files.lines"
                            + " в try-with-resources) либо сразу передавайте поток дальше"
                            + " (InputStream.transferTo в выходной поток)")
                    .withConfidence(confidence));
        }
    }

    // Текст вызова собирается только для находки: метод проходит по каждому вызову проекта
    private Optional<String> subject(MethodCallExpr call, Set<Node> reportedLoops) {
        Optional<Node> copyLoop = copyLoop(call);
        if (copyLoop.isPresent()) {
            // Цикл копирования - одна находка, сколько бы записей в буфер в нем ни было
            return reportedLoops.add(copyLoop.get())
                    ? Optional.of("Цикл копирует поток в буфер в памяти целиком (" + shown(call) + ")")
                    : Optional.empty();
        }
        if (copiesIntoBuffer(call)) {
            return Optional.of(shown(call) + " копирует поток в буфер в памяти целиком");
        }
        return readsWhole(call) ? Optional.of(shown(call) + " читает содержимое в память целиком") : Optional.empty();
    }

    private static String shown(MethodCallExpr call) {
        return "'" + Nodes.text(call) + "'";
    }

    private boolean readsWhole(MethodCallExpr call) {
        String name = call.getNameAsString();
        Expression scope = Nodes.unwrap(call.getScope().orElseThrow());
        boolean utility = READS.entrySet().stream()
                .anyMatch(entry -> entry.getValue().contains(name) && MethodCalls.isCallOn(call, entry.getKey(), name));
        if (utility) {
            return !(TEXT_READS.contains(name) && HTTP_BODY.matcher(call.getArguments().toString()).find());
        }
        // resource.getContentAsByteArray(); у ContentCachingResponseWrapper тот же метод отдает уже готовый буфер
        if (CONTENT_AS_BYTES.equals(name) && call.getArguments().isEmpty()) {
            return RESOURCE.matcher(scope.toString()).find()
                    || LocalTypes.typeOf(scope).filter(type -> RESOURCE.matcher(type).find()).isPresent();
        }
        return READ_ALL_BYTES.equals(name) && call.getArguments().isEmpty()
                && !(scope.isMethodCallExpr() && UPLOAD_SOURCES.contains(scope.asMethodCallExpr().getNameAsString()));
    }

    // in.transferTo(buffer), IOUtils.copy(in, buffer), Files.copy(path, buffer)
    private boolean copiesIntoBuffer(MethodCallExpr call) {
        boolean copy = TRANSFER_TO.equals(call.getNameAsString())
                || COPY.equals(call.getNameAsString())
                && COPY_UTILITIES.contains(MethodCalls.receiverName(call.getScope().orElseThrow()));
        return copy && call.getArguments().stream().anyMatch(InMemoryFiles::isByteBuffer);
    }

    // while ((read = in.read(chunk)) != -1) { buffer.write(chunk, 0, read); }
    private Optional<Node> copyLoop(MethodCallExpr call) {
        boolean writesChunk = WRITE.equals(call.getNameAsString())
                && call.getArguments().size() == COPY_WRITE_ARGUMENTS
                && InMemoryFiles.isByteBuffer(call.getScope().orElseThrow());
        if (!writesChunk) {
            return Optional.empty();
        }
        return Loops.enclosingIteration(call).filter(InMemoryFiles::isStreamCopy);
    }

    private boolean isBounded(MethodCallExpr call) {
        return BOUNDED.matcher(context(call)).find()
                || call.findAncestor(MethodDeclaration.class)
                .filter(method -> BOUNDED.matcher(method.getNameAsString()).find())
                .isPresent()
                || call.findAncestor(TypeDeclaration.class)
                .filter(type -> BOUNDED.matcher(type.getNameAsString()).find())
                .isPresent();
    }

    // Данные пришли извне или названы файлом: размер их заранее не известен
    private boolean isData(MethodCallExpr call) {
        return InMemoryFiles.hasFileWord(context(call))
                || call.findAncestor(MethodDeclaration.class)
                .filter(method -> method.getParameters().stream()
                        .anyMatch(parameter -> EXTERNAL_TYPES.contains(LocalTypes.typeName(parameter.getType()))))
                .isPresent();
    }

    // Что читают: аргументы и получатель вызова вместе с тем, чем они были заданы. Имя самого
    // служебного класса (Files, FileUtils) сюда не входит - оно ничего не говорит о данных
    private String context(MethodCallExpr call) {
        StringBuilder context = new StringBuilder();
        List<Expression> parts = new ArrayList<>(call.getArguments());
        Expression scope = Nodes.unwrap(call.getScope().orElseThrow());
        if (!READS.containsKey(scope.toString()) && !COPY_UTILITIES.contains(scope.toString())) {
            parts.add(scope);
        }
        for (Expression part : parts) {
            Expression value = Nodes.unwrap(part);
            context.append(value).append(' ');
            if (value.isNameExpr()) {
                LocalTypes.findInitializer(value).ifPresent(initializer -> context.append(initializer).append(' '));
            }
        }
        return context.toString();
    }
}
