package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
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
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loops;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.StreamSources;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * while ((b = in.read()) != -1) над потоком без буфера: каждый байт читается отдельным обращением к операционной
 * системе или к сети. То же с out.write(b) в цикле. Между циклом и потоком нужен буфер.
 */
@Component
public class SingleByteIoRule extends AbstractRule {
    private static final String READ = "read";
    private static final String WRITE = "write";
    private static final String FILE_INPUT = "FileInputStream";
    private static final Set<String> READERS = Set.of("InputStreamReader", "FileReader");
    // socket.getInputStream(), url.openStream(), entity.getContent(): поток приходит из сети или из хранилища
    private static final Set<String> OPENERS = Set.of(
            "getInputStream", "openStream", "getContent", "getErrorStream", "getObjectContent");
    private static final Set<String> SINGLE_UNIT = Set.of("int", "byte", "char");

    private enum Source { FILE, REMOTE, READER }

    @Override
    public String code() {
        return RuleCodes.SINGLE_BYTE_IO_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет циклы, которые читают или пишут по одному байту либо символу в поток без"
                + " буфера";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        // Один цикл с одним потоком - одна находка, сколько бы раз в нем ни читали
        Set<String> reported = new HashSet<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            Optional<Node> loop = Loops.enclosingIteration(call);
            if (loop.isEmpty() || call.getScope().filter(Expression::isNameExpr).isEmpty()
                    || TestClasses.isInside(call)) {
                continue;
            }
            Optional<Violation> found = READ.equals(call.getNameAsString()) && call.getArguments().isEmpty()
                    ? checkRead(sourceFile, call)
                    : checkWrite(sourceFile, call);
            if (found.isPresent() && reported.add(System.identityHashCode(loop.get()) + ":" + call.getScope().get())) {
                violations.add(found.get());
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    private Optional<Violation> checkRead(SourceFile sourceFile, MethodCallExpr call) {
        List<Expression> values = StreamSources.valuesOf(call.getScope().get());
        if (values.isEmpty() || values.stream().anyMatch(value -> source(value) == null)) {
            return Optional.empty();
        }
        Source source = source(values.get(0));
        String origin = Nodes.text(values.get(0));
        if (source == Source.READER) {
            return Optional.of(violation(sourceFile, call,
                    "'" + call + "' в цикле читает по одному символу из '" + origin + "': на каждый символ берется"
                            + " блокировка и запускается декодер; оберните источник в BufferedReader")
                    .withLevel(ErrorLevel.INFO));
        }
        Violation violation = violation(sourceFile, call,
                "'" + call + "' в цикле читает по одному байту из потока без буфера ('" + origin + "'): каждый байт -"
                        + " отдельное обращение к операционной системе или к сети; оберните поток в"
                        + " BufferedInputStream либо читайте блоками через read(byte[])");
        // Что вернул чужой метод, по вызову не видно: поток может оказаться уже с буфером
        return Optional.of(source == Source.REMOTE ? violation.withConfidence(Confidence.PROBABLE) : violation);
    }

    private Optional<Violation> checkWrite(SourceFile sourceFile, MethodCallExpr call) {
        if (!WRITE.equals(call.getNameAsString()) || call.getArguments().size() != 1
                || !isSingleUnit(call.getArgument(0))) {
            return Optional.empty();
        }
        return StreamSources.rawFileStream(call.getScope().get()).map(file -> violation(sourceFile, call,
                "'" + call + "' в цикле пишет по одному байту в файл без буфера ('" + Nodes.text(file) + "'):"
                        + " каждый байт - отдельное обращение к операционной системе; оберните поток в"
                        + " BufferedOutputStream"));
    }

    // null - поток с буфером либо неизвестно какой
    private Source source(Expression value) {
        if (value.isObjectCreationExpr()) {
            String type = value.asObjectCreationExpr().getType().getNameAsString();
            if (READERS.contains(type)) {
                return Source.READER;
            }
            return FILE_INPUT.equals(type) ? Source.FILE : null;
        }
        if (StreamSources.isRawFileStream(value)) {
            return Source.FILE;
        }
        return value.isMethodCallExpr() && OPENERS.contains(value.asMethodCallExpr().getNameAsString())
                ? Source.REMOTE
                : null;
    }

    // out.write(b), out.write('\n'), out.write((byte) code) - но не out.write(buffer)
    private boolean isSingleUnit(Expression argument) {
        Expression value = Nodes.unwrap(argument);
        return value.isIntegerLiteralExpr() || value.isCharLiteralExpr() || value.isCastExpr()
                || LocalTypes.typeOf(value).filter(SINGLE_UNIT::contains).isPresent();
    }
}
