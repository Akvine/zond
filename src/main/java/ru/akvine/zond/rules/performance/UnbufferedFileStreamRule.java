package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.StreamSources;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * new ObjectInputStream(new FileInputStream(file)), new GZIPOutputStream(new FileOutputStream(file)):
 * обертка читает и пишет по несколько байт, а файловый поток под ней каждую такую порцию отдает операционной
 * системе отдельным вызовом. Между ними нужен BufferedInputStream / BufferedOutputStream.
 */
@Component
public class UnbufferedFileStreamRule extends AbstractRule {
    // Обертки, которые обращаются к нижнему потоку мелкими порциями: по полю, по байту или блоками по 512 байт
    private static final Pattern SMALL_PORTIONS = Pattern.compile(
            "(Object|Data)(Input|Output)Stream|PrintStream"
                    + "|(GZIP|Zip|Jar|Inflater|Deflater)(Input|Output)Stream|GzipCompressor(Input|Output)Stream");
    // new GZIPInputStream(in, 65536): размер своего буфера задан явно
    private static final Pattern SIZED = Pattern.compile("GZIP|Inflater|Deflater");
    private static final String INPUT = "Input";

    @Override
    public String code() {
        return RuleCodes.UNBUFFERED_FILE_STREAM_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет файловые потоки без буфера под обертками, которые читают и пишут мелкими"
                + " порциями: ObjectInputStream, DataOutputStream, PrintStream, GZIPOutputStream";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ObjectCreationExpr wrapper : sourceFile.unit().findAll(ObjectCreationExpr.class)) {
            String type = wrapper.getType().getNameAsString();
            if (!SMALL_PORTIONS.matcher(type).matches() || wrapper.getArguments().isEmpty()
                    || hasOwnBufferSize(wrapper) || TestClasses.isInside(wrapper)) {
                continue;
            }
            StreamSources.rawFileStream(wrapper.getArgument(0)).ifPresent(file -> violations.add(violation(
                    sourceFile, wrapper,
                    "'" + type + "' работает с файлом без буфера ('" + Nodes.text(file) + "'): данные идут мелкими"
                            + " порциями, и каждая - отдельное обращение к операционной системе; оберните файловый"
                            + " поток в " + (type.contains(INPUT) ? "BufferedInputStream" : "BufferedOutputStream"))));
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

    private boolean hasOwnBufferSize(ObjectCreationExpr wrapper) {
        if (wrapper.getArguments().size() < 2 || !SIZED.matcher(wrapper.getType().getNameAsString()).find()) {
            return false;
        }
        Expression second = wrapper.getArgument(1);
        return !second.isBooleanLiteralExpr() && !second.isObjectCreationExpr();
    }
}
