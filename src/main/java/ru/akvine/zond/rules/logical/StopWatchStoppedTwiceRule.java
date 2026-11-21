package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Секундомер останавливают и в конце {@code try}, и в {@code finally}. При успешном выполнении первый stop() уже
 * сработал, а второй бросает IllegalStateException: и у StopWatch из Spring и commons-lang, и у Stopwatch из Guava
 * остановить можно только идущий секундомер. Исключение из finally заменяет собой и возвращаемое значение - метод
 * падает после того, как работа сделана
 */
@Component
public class StopWatchStoppedTwiceRule extends AbstractRule {
    private static final Set<String> STOPWATCH_TYPES = Set.of("StopWatch", "Stopwatch");
    // Секундомеры, у которых повторный stop() - ошибка. У одноименных классов других библиотек (perf4j, slf4j-ext)
    // и у своего класса проекта он может быть разрешен
    private static final Set<String> STRICT_STOPWATCHES = Set.of(
            "org.springframework.util.StopWatch",
            "org.apache.commons.lang3.time.StopWatch",
            "org.apache.commons.lang.time.StopWatch",
            "com.google.common.base.Stopwatch",
            "cn.hutool.core.date.StopWatch",
            "org.apache.hadoop.util.StopWatch");
    private static final String STOP = "stop";
    // reset() секундомер не запускает: после него stop() падает так же
    private static final Set<String> RESTARTS = Set.of("start", "resume", "restart");
    private static final int MAX_LINES = 5;

    @Override
    public String code() {
        return RuleCodes.STOPWATCH_STOPPED_TWICE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет секундомер, который останавливают и в try, и в finally";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        if (!usesStrictStopwatch(sourceFile)) {
            return violations;
        }
        for (ClassOrInterfaceDeclaration type : sourceFile.findAll(ClassOrInterfaceDeclaration.class)) {
            List<MethodCallExpr> stops = new ArrayList<>();
            for (TryStmt statement : type.findAll(TryStmt.class)) {
                boolean own = statement.findAncestor(ClassOrInterfaceDeclaration.class).filter(owner -> owner == type)
                        .isPresent();
                if (own && statement.getFinallyBlock().isPresent() && !TestClasses.isInside(statement)) {
                    stops.addAll(repeatedStops(statement.getTryBlock(), statement.getFinallyBlock().get()));
                }
            }
            if (stops.isEmpty()) {
                continue;
            }
            // Один и тот же шаблон обычно размножен по всем методам класса: сообщаем о первом и называем остальные
            String others = stops.size() == 1 ? "" : " То же в этом классе на строках " + stops.stream()
                    .skip(1)
                    .limit(MAX_LINES)
                    .map(stop -> String.valueOf(stop.getBegin().map(position -> position.line).orElse(0)))
                    .collect(Collectors.joining(", "))
                    + (stops.size() - 1 > MAX_LINES ? " и еще в " + (stops.size() - 1 - MAX_LINES) + " местах" : "")
                    + ".";
            violations.add(violation(sourceFile, stops.get(0),
                    "'" + stops.get(0) + "' в finally после остановки в самом try: при успешном выполнении"
                            + " секундомер уже остановлен, и повторный stop() бросает IllegalStateException -"
                            + " метод падает после того, как работа сделана, а результат теряется; оставьте"
                            + " остановку только в finally либо проверяйте isRunning()." + others));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    // timer.stop(); отдельным оператором у переменной-секундомера
    private Optional<MethodCallExpr> stopOf(Statement statement) {
        if (!statement.isExpressionStmt()) {
            return Optional.empty();
        }
        Expression expression = Nodes.unwrap(statement.asExpressionStmt().getExpression());
        return Optional.of(expression)
                .filter(Expression::isMethodCallExpr)
                .map(Expression::asMethodCallExpr)
                .filter(call -> STOP.equals(call.getNameAsString()) && call.getArguments().isEmpty())
                .filter(call -> call.getScope()
                        .filter(scope -> LocalTypes.typeOf(scope).filter(STOPWATCH_TYPES::contains).isPresent())
                        .isPresent());
    }

    // Имя StopWatch в файле означает секундомер одной из известных библиотек: по явному импорту либо по импорту
    // всего пакета, когда явного нет
    private boolean usesStrictStopwatch(SourceFile sourceFile) {
        List<ImportDeclaration> imports = sourceFile.unit().getImports().stream()
                .filter(declaration -> !declaration.isStatic())
                .toList();
        List<String> explicit = imports.stream()
                .filter(declaration -> !declaration.isAsterisk())
                .map(ImportDeclaration::getNameAsString)
                .filter(name -> STOPWATCH_TYPES.contains(name.substring(name.lastIndexOf('.') + 1)))
                .toList();
        if (!explicit.isEmpty()) {
            return STRICT_STOPWATCHES.containsAll(explicit);
        }
        return imports.stream()
                .filter(ImportDeclaration::isAsterisk)
                .map(ImportDeclaration::getNameAsString)
                .anyMatch(packageName -> STRICT_STOPWATCHES.stream()
                        .anyMatch(known -> known.substring(0, known.lastIndexOf('.')).equals(packageName)));
    }

    /**
     * @return вызовы stop() в finally у секундомера, который к концу самого блока try уже остановлен и которого
     * в finally перед этим не запускали заново
     */
    private List<MethodCallExpr> repeatedStops(BlockStmt tryBlock, BlockStmt finallyBlock) {
        List<MethodCallExpr> repeated = new ArrayList<>();
        Set<String> restarted = new HashSet<>();
        for (Statement statement : finallyBlock.getStatements()) {
            Optional<MethodCallExpr> stop = stopOf(statement);
            if (stop.isEmpty()) {
                statement.findAll(MethodCallExpr.class).stream()
                        .filter(call -> RESTARTS.contains(call.getNameAsString()))
                        .forEach(call -> call.getScope().ifPresent(scope -> restarted.add(scope.toString())));
                continue;
            }
            Expression timer = stop.get().getScope().get();
            if (!restarted.remove(timer.toString()) && isStoppedAtEndOf(tryBlock, timer)) {
                repeated.add(stop.get());
            }
        }
        return repeated;
    }

    // Среди операторов самого блока try есть stop() того же секундомера, и после него его не запускают заново
    private boolean isStoppedAtEndOf(BlockStmt tryBlock, Expression timer) {
        boolean stopped = false;
        for (Statement statement : tryBlock.getStatements()) {
            Optional<MethodCallExpr> stop = stopOf(statement);
            if (stop.filter(call -> call.getScope().get().toString().equals(timer.toString())).isPresent()) {
                stopped = true;
            } else if (statement.findAll(MethodCallExpr.class).stream().anyMatch(call -> isRestart(call, timer))) {
                stopped = false;
            }
        }
        return stopped;
    }

    private boolean isRestart(MethodCallExpr call, Expression timer) {
        return RESTARTS.contains(call.getNameAsString())
                && call.getScope().filter(scope -> scope.toString().equals(timer.toString())).isPresent();
    }
}
