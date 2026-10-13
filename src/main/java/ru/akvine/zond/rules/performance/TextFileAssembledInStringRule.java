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
import java.util.regex.Pattern;

/**
 * Текстовый файл (CSV, SQL, XML) копится в StringBuilder или склеивается в одну строку, а затем
 * превращается в массив байт. В памяти в этот момент три копии: накопитель, строка и ее байты.
 */
@Component
public class TextFileAssembledInStringRule extends AbstractRule {
    private static final String GET_BYTES = "getBytes";
    private static final String TO_STRING = "toString";
    private static final String JOIN = "join";
    private static final String STRING = "String";
    private static final String COLLECT = "collect";
    private static final String JOINING = "joining";

    // "\n", System.lineSeparator(), LINE_SEPARATOR: разделитель строк выдает многострочный файл
    private static final Pattern LINE_BREAK =
            Pattern.compile("\\\\n|\\\\r|(?i)lineSeparator|line_?separator|new_?line|line_?break|\\bEOL\\b|CRLF");

    private record Evidence(String text, Confidence confidence) {
    }

    @Override
    public String code() {
        return RuleCodes.TEXT_FILE_ASSEMBLED_IN_STRING_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет текстовые файлы, которые собираются в StringBuilder или в одну строку"
                + " и затем превращаются в массив байт";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!GET_BYTES.equals(call.getNameAsString()) || call.getScope().isEmpty()
                    || TestClasses.isInside(call) || CodeContexts.isStartup(call)) {
                continue;
            }
            Expression text = origin(call.getScope().get());
            accumulated(text, call).or(() -> joined(text)).ifPresent(found -> violations.add(
                    violation(sourceFile, call.getName(),
                            "'" + Nodes.text(call) + "': текст файла собран в памяти целиком (" + found.text()
                                    + ") и превращается в массив байт - в памяти одновременно накопитель, строка"
                                    + " и ее байты; пишите строки сразу в Writer поверх выходного потока"
                                    + " (BufferedWriter, PrintWriter), не собирая файл в строку")
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

    // String sql = builder.toString(); ... sql.getBytes() - смотрим, откуда взялась строка
    private Expression origin(Expression scope) {
        Expression value = Nodes.unwrap(scope);
        if (value.isNameExpr()) {
            return LocalTypes.findInitializer(value).map(Nodes::unwrap).orElse(value);
        }
        return value;
    }

    private Optional<Evidence> accumulated(Expression text, MethodCallExpr usage) {
        if (!text.isMethodCallExpr() || !TO_STRING.equals(text.asMethodCallExpr().getNameAsString())) {
            return Optional.empty();
        }
        Optional<VariableDeclarator> buffer = text.asMethodCallExpr().getScope()
                .map(Nodes::unwrap)
                .filter(Expression::isNameExpr)
                .flatMap(LocalTypes::findDeclaration)
                .filter(declaration -> declaration instanceof VariableDeclarator)
                .map(declaration -> (VariableDeclarator) declaration)
                .filter(InMemoryFiles::isTextBuffer);
        Optional<Node> callable = buffer.flatMap(Nodes::enclosingCallable).filter(method -> method.isAncestorOf(usage));
        if (buffer.isEmpty() || callable.isEmpty()) {
            return Optional.empty();
        }
        return InMemoryFiles
                .repeatedWrite(callable.get(), buffer.get(),
                        InMemoryFiles.writers(callable.get(), buffer.get().getNameAsString()))
                .map(write -> new Evidence(
                        "накоплен в '" + buffer.get().getNameAsString() + "' в цикле по данным", Confidence.PROBABLE));
    }

    // String.join("\n", lines), lines.stream().collect(Collectors.joining("\n"))
    private Optional<Evidence> joined(Expression text) {
        if (!text.isMethodCallExpr()) {
            return Optional.empty();
        }
        MethodCallExpr call = text.asMethodCallExpr();
        Optional<Expression> separator = Optional.empty();
        if (JOIN.equals(call.getNameAsString()) && call.getArguments().size() > 1
                && call.getScope().filter(scope -> STRING.equals(MethodCalls.receiverName(scope))).isPresent()) {
            separator = Optional.of(call.getArgument(0));
        } else if (COLLECT.equals(call.getNameAsString()) && call.getArguments().size() == 1) {
            separator = Optional.of(Nodes.unwrap(call.getArgument(0)))
                    .filter(Expression::isMethodCallExpr)
                    .map(Expression::asMethodCallExpr)
                    .filter(collector -> JOINING.equals(collector.getNameAsString())
                            && !collector.getArguments().isEmpty())
                    .map(collector -> collector.getArgument(0));
        }
        // Склейка через запятую - это значение; по разделителю строк видно, что собирают файл
        return separator
                .filter(value -> LINE_BREAK.matcher(value.toString()).find())
                .map(value -> new Evidence("все строки склеены в одну", Confidence.SUSPICION));
    }
}
