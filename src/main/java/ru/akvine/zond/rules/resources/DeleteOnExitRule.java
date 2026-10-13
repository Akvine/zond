package ru.akvine.zond.rules.resources;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.List;

/**
 * {@code File.deleteOnExit()} в сервисе. Файл удалится только при остановке JVM - до того он лежит
 * на диске, а его путь добавляется в список, который не очищается никогда. Сервис, создающий
 * временный файл на каждый запрос, так расходует и диск, и память.
 */
@Component
public class DeleteOnExitRule extends AbstractRule {
    private static final String DELETE_ON_EXIT = "deleteOnExit";
    private static final String MAIN = "main";

    @Override
    public String code() {
        return RuleCodes.DELETE_ON_EXIT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет File.deleteOnExit() - удаление временного файла, отложенное до остановки JVM";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> DELETE_ON_EXIT.equals(call.getNameAsString())
                        && call.getArguments().isEmpty() && call.getScope().isPresent())
                // Один файл на весь срок работы приложения и утилита командной строки - не утечка
                .filter(call -> !TestClasses.isInside(call) && !CodeContexts.isStartup(call) && !isInMain(call))
                .map(call -> violation(sourceFile, call.getName(),
                        "'" + Nodes.text(call) + "': файл удалится только при остановке JVM, а до того и сам он"
                                + " лежит на диске, и его путь - в памяти (список растет с каждым вызовом и не"
                                + " очищается); удаляйте временный файл сами в finally (Files.deleteIfExists)"
                                + " либо открывайте его с StandardOpenOption.DELETE_ON_CLOSE"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.RESOURCE;
    }

    private boolean isInMain(MethodCallExpr call) {
        return call.findAncestor(MethodDeclaration.class)
                .filter(method -> method.isStatic() && MAIN.equals(method.getNameAsString()))
                .isPresent();
    }
}
