package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.TestPaths;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.MethodCalls;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class ConsoleOutputRule extends AbstractRule {
    private static final String PRINT_STACK_TRACE = "printStackTrace";
    private static final String SYSTEM = "System";
    private static final Set<String> CONSOLE_STREAMS = Set.of("out", "err");
    private static final String MAIN = "main";
    // @Command из picocli и Spring Shell: класс - команда консольной программы
    private static final Set<String> COMMAND_ANNOTATIONS = Set.of("Command", "ShellComponent");
    private static final Set<String> CLI_PACKAGES = Set.of(
            "org.apache.commons.cli.", "com.beust.jcommander.", "joptsimple.", "org.kohsuke.args4j.");
    private static final Pattern CONSOLE_INPUT =
            Pattern.compile("System\\.in\\b|System\\.console\\(\\)|\\bLineReader\\b|org\\.jline\\.");

    @Override
    public String code() {
        return RuleCodes.CONSOLE_OUTPUT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет printStackTrace() и вывод через System.out / System.err вместо логгера";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        // Пример или команда консольной программы (picocli, Spring Shell): ее вывод и есть консоль
        if (TestPaths.isSample(sourceFile.path()) || isConsoleCommand(sourceFile)) {
            return violations;
        }
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (isInMainProgram(call)) {
                continue;
            }
            if (PRINT_STACK_TRACE.equals(call.getNameAsString())
                    && call.getArguments().isEmpty()
                    && call.getScope().isPresent()) {
                violations.add(violation(sourceFile, call,
                        "'" + call + "' пишет в консоль мимо логгера: запись не попадет в файлы логов"
                                + " и потеряет контекст; используйте log.error(..., exception)"));
            }

            if (call.getScope().filter(this::isConsoleStream).isPresent()) {
                violations.add(violation(sourceFile, call,
                        "Вывод через " + call.getScope().get() + " вместо логгера: нет уровня, времени"
                                + " и возможности отключить; используйте логгер"));
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
        return ErrorType.CODE_SMELL;
    }

    // Команда picocli или Spring Shell либо интерактивная консоль (ConsoleApp в Hazelcast): main и чтение ввода
    // через System.in, System.console(), LineReader из JLine - вывод в любом ее методе и есть интерфейс
    private boolean isConsoleCommand(SourceFile sourceFile) {
        // TopicListSubCommand.execute(CommandLine commandLine, ...) в mqadmin RocketMQ: разбор аргументов
        // через Commons CLI, JCommander, jopt-simple или args4j - это команда консольной программы
        boolean command = sourceFile.unit().getTypes().stream()
                .anyMatch(type -> Annotations.hasAny(type, COMMAND_ANNOTATIONS))
                || sourceFile.unit().getImports().stream()
                .anyMatch(declaration -> CLI_PACKAGES.stream().anyMatch(declaration.getNameAsString()::startsWith));
        boolean hasMain = sourceFile.unit().getTypes().stream()
                .flatMap(type -> type.getMethods().stream())
                .anyMatch(method -> MAIN.equals(method.getNameAsString()) && method.isStatic());
        return command || hasMain && CONSOLE_INPUT.matcher(sourceFile.unit().toString()).find();
    }

    // Вывод в main и в его закрытых статических помощниках (printUsage): консольная программа пишет результат.
    // main, забытый в рабочем классе, не прячет вывод из остальных его методов
    private boolean isInMainProgram(MethodCallExpr call) {
        return call.findAncestor(MethodDeclaration.class)
                .filter(method -> method.isStatic() && (MAIN.equals(method.getNameAsString()) || method.isPrivate()))
                .filter(method -> method.findAncestor(TypeDeclaration.class).stream()
                        .map(type -> (TypeDeclaration<?>) type)
                        .flatMap(type -> type.getMethods().stream())
                        .anyMatch(other -> MAIN.equals(other.getNameAsString()) && other.isStatic()))
                .isPresent();
    }

    // System.out, System.err
    private boolean isConsoleStream(Expression scope) {
        return scope.isFieldAccessExpr()
                && CONSOLE_STREAMS.contains(scope.asFieldAccessExpr().getNameAsString())
                && MethodCalls.isType(scope.asFieldAccessExpr().getScope(), SYSTEM);
    }
}
