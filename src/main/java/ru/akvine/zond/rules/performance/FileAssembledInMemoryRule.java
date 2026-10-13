package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.InMemoryFiles;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Файл собирается в памяти: строки пишутся в ByteArrayOutputStream, а потом из него берут массив байт.
 * Пока файл мал, это незаметно; с ростом выборки память на запрос растет вместе с ним, а toByteArray()
 * делает еще одну копию. Потоковая запись в настоящий выходной поток занимает только буфер.
 */
@Component
public class FileAssembledInMemoryRule extends AbstractRule {
    private static final String TO_BYTE_ARRAY = "toByteArray";
    private static final int MAX_SHOWN_LENGTH = 60;
    private static final String ELLIPSIS = "...";

    // workbook.write(out), document.save(out)
    private static final Set<String> DOCUMENT_WRITES = Set.of("write", "save", "writeTo", "store");
    private static final Pattern DOCUMENT =
            Pattern.compile("(?i)(workbook|document|presentation|slideshow|wb|book)$");

    // csvExporter.write(rows, out), reportService.exportTo(out)
    private static final Pattern EXPORT_ACTION =
            Pattern.compile("(?i)^(write|export|generate|render|print|dump|save|fill|build|create|convert)");
    private static final Pattern EXPORT_SUBJECT =
            Pattern.compile("(?i)(csv|xlsx?|excel|pdf|zip|report|export|archive|sheet|dump|jasper)");

    private record Evidence(String text, Confidence confidence) {
    }

    @Override
    public String code() {
        return RuleCodes.FILE_ASSEMBLED_IN_MEMORY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет файлы, которые собираются в памяти целиком через ByteArrayOutputStream";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (VariableDeclarator buffer : sourceFile.unit().findAll(VariableDeclarator.class)) {
            if (!InMemoryFiles.isByteBuffer(buffer) || TestClasses.isInside(buffer) || CodeContexts.isStartup(buffer)) {
                continue;
            }
            Optional<Node> callable = Nodes.enclosingCallable(buffer);
            if (callable.isEmpty()) {
                continue;
            }
            evidence(callable.get(), buffer).ifPresent(found -> violations.add(
                    violation(sourceFile, buffer, message(callable.get(), buffer, found))
                            .withConfidence(found.confidence())));
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

    private Optional<Evidence> evidence(Node callable, VariableDeclarator buffer) {
        Set<String> writers = InMemoryFiles.writers(callable, buffer.getNameAsString());
        Optional<MethodCallExpr> repeated = InMemoryFiles.repeatedWrite(callable, buffer, writers);
        if (repeated.isPresent()) {
            return Optional.of(new Evidence(
                    "запись в него идет в цикле по данным (" + shown(repeated.get()) + ")", Confidence.PROBABLE));
        }
        List<MethodCallExpr> receivers = callable.findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getScope().isPresent() && passes(call, writers))
                .toList();
        Optional<MethodCallExpr> document = receivers.stream().filter(this::writesDocument).findFirst();
        if (document.isPresent()) {
            return Optional.of(new Evidence(
                    "в него целиком записывается документ (" + shown(document.get()) + ")", Confidence.PROBABLE));
        }
        // Что и сколько пишет вызванный метод, отсюда не видно - судим по названию
        return receivers.stream()
                .filter(this::looksLikeExport)
                .findFirst()
                .map(export -> new Evidence("его заполняет " + shown(export), Confidence.SUSPICION));
    }

    private boolean passes(MethodCallExpr call, Set<String> writers) {
        return call.getArguments().stream()
                .map(Nodes::unwrap)
                .filter(Expression::isNameExpr)
                .anyMatch(argument -> writers.contains(argument.asNameExpr().getNameAsString()));
    }

    private boolean writesDocument(MethodCallExpr call) {
        Expression scope = call.getScope().orElseThrow();
        return DOCUMENT_WRITES.contains(call.getNameAsString())
                && (LocalTypes.typeOf(scope).filter(type -> DOCUMENT.matcher(type).find()).isPresent()
                || DOCUMENT.matcher(MethodCalls.receiverName(scope)).find());
    }

    private boolean looksLikeExport(MethodCallExpr call) {
        String name = call.getNameAsString();
        String receiver = MethodCalls.receiverName(call.getScope().orElseThrow());
        return EXPORT_ACTION.matcher(name).find()
                && (EXPORT_SUBJECT.matcher(name).find() || EXPORT_SUBJECT.matcher(receiver).find());
    }

    private String message(Node callable, VariableDeclarator buffer, Evidence evidence) {
        String name = buffer.getNameAsString();
        boolean copied = callable.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> TO_BYTE_ARRAY.equals(call.getNameAsString())
                        && call.getScope().filter(scope -> scope.toString().equals(name)).isPresent());
        return "'" + name + "' собирает файл в памяти целиком: " + evidence.text()
                + (copied ? ", а " + name + ".toByteArray() делает еще одну копию" : "")
                + "; память на запрос растет вместе с размером файла, и несколько одновременных выгрузок ее"
                + " исчерпают; пишите сразу в выходной поток - принимайте OutputStream параметром"
                + " (StreamingResponseBody для ответа, файл на диске)";
    }

    private static String shown(MethodCallExpr call) {
        String text = Nodes.text(call).replaceAll("\\s+", " ");
        return "'" + (text.length() > MAX_SHOWN_LENGTH ? text.substring(0, MAX_SHOWN_LENGTH) + ELLIPSIS : text) + "'";
    }
}
