package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.JavaVersions;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Component
public class DefaultCharsetRule extends AbstractRule implements ContextRule {
    // JEP 400: с Java 18 кодировка по умолчанию - UTF-8 на любой машине
    private static final int UTF8_BY_DEFAULT_SINCE = 18;
    private static final int LAST_ASCII = 127;
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

    /**
     * Проект, который собирают под Java 18 и новее, запустить на более старой нельзя, а с Java 18 кодировка по
     * умолчанию от машины не зависит: находке не о чем говорить
     */
    @Override
    public List<Violation> checkContext(ScanContext context) {
        if (JavaVersions.lowest(context.textFiles()).orElse(0) >= UTF8_BY_DEFAULT_SINCE) {
            return List.of();
        }
        return context.sources().stream().flatMap(sourceFile -> check(sourceFile).stream()).toList();
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();

        // text.getBytes() именно на строке: у MultipartFile.getBytes() кодировки нет
        for (MethodCallExpr call : sourceFile.findAll(MethodCallExpr.class)) {
            boolean onString = call.getScope().flatMap(LocalTypes::typeOf).filter(STRING::equals).isPresent();
            if (GET_BYTES.equals(call.getNameAsString()) && call.getArguments().isEmpty() && onString
                    && !isAsciiLiteral(call.getScope().get())) {
                violations.add(report(sourceFile, call));
            }
        }

        for (ObjectCreationExpr creation : sourceFile.findAll(ObjectCreationExpr.class)) {
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

    // "".getBytes(), "OK".getBytes(): латиница и цифры кодируются одинаково в любой кодировке по умолчанию
    private boolean isAsciiLiteral(Expression scope) {
        Expression value = Nodes.unwrap(scope);
        return value.isStringLiteralExpr()
                && value.asStringLiteralExpr().asString().chars().allMatch(symbol -> symbol <= LAST_ASCII);
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
