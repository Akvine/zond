package ru.akvine.zond.rules.logical;

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
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Component
public class DefaultCharsetRule extends AbstractRule {
    private static final String GET_BYTES = "getBytes";
    private static final String STRING = "String";
    private static final String BYTE_ARRAY = "byte[]";
    private static final Set<String> CONSOLE_STREAMS = Set.of("System.in", "System.out", "System.err");

    // Классы, которые без явной кодировки берут кодировку операционной системы
    private static final Set<String> CHARSET_DEPENDENT_TYPES =
            Set.of("FileReader", "FileWriter", "InputStreamReader", "OutputStreamWriter");

    // Методы, которые возвращают массив байтов
    private static final Set<String> BYTE_SOURCES = Set.of("readAllBytes", "toByteArray", "getBytes", "getBody", "decode");

    @Override
    public String code() {
        return RuleCodes.DEFAULT_CHARSET_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет преобразование между байтами и текстом без указания кодировки";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();

        // text.getBytes() именно на строке: у MultipartFile.getBytes() кодировки нет
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            boolean onString = call.getScope().flatMap(LocalTypes::typeOf).filter(STRING::equals).isPresent();
            if (GET_BYTES.equals(call.getNameAsString()) && call.getArguments().isEmpty() && onString) {
                violations.add(report(sourceFile, call));
            }
        }

        for (ObjectCreationExpr creation : sourceFile.unit().findAll(ObjectCreationExpr.class)) {
            if (dependsOnDefault(creation)) {
                violations.add(report(sourceFile, creation));
            }
        }

        violations.sort(Comparator.comparingInt(Violation::line));
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private Violation report(SourceFile sourceFile, Expression expression) {
        return violation(sourceFile, expression,
                "'" + expression + "' без кодировки: используется кодировка той машины, где запущен код, на"
                        + " сервере и на компьютере разработчика она может отличаться - кириллица превратится"
                        + " в вопросительные знаки; укажите StandardCharsets.UTF_8");
    }

    // new String(bytes), new FileReader(file), new InputStreamReader(stream)
    private boolean dependsOnDefault(ObjectCreationExpr creation) {
        if (creation.getArguments().size() != 1) {
            return false;
        }
        String type = creation.getType().getNameAsString();
        Expression argument = creation.getArgument(0);
        return STRING.equals(type) && isBytes(argument)
                || CHARSET_DEPENDENT_TYPES.contains(type) && !isConsoleStream(argument);
    }

    /**
     * new InputStreamReader(System.in): у консоли кодировка своя, и это как раз кодировка машины, где запущен
     * код. UTF-8 здесь сломает ввод кириллицы в консоли Windows
     */
    private boolean isConsoleStream(Expression argument) {
        return CONSOLE_STREAMS.contains(Nodes.unwrap(argument).toString());
    }

    // Переменная типа byte[] либо вызов, который возвращает байты
    private boolean isBytes(Expression argument) {
        Expression value = Nodes.unwrap(argument);
        if (value.isMethodCallExpr()) {
            return BYTE_SOURCES.contains(value.asMethodCallExpr().getNameAsString());
        }
        return LocalTypes.typeOf(value).filter(BYTE_ARRAY::equals).isPresent();
    }
}
