package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class CheckRepositoryCallInLoopRule extends AbstractRule {
    // Типы не разрешаем, поэтому репозиторий узнаем по имени объекта
    private static final Pattern REPOSITORY = Pattern.compile(".*(repository|repo|dao)$", Pattern.CASE_INSENSITIVE);

    private static final Pattern CONSTANT_NAME = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    // Операции, которые выполняют лямбду для каждого элемента
    private static final Set<String> ITERATING_METHODS = Set.of(
            "forEach", "forEachOrdered", "map", "flatMap", "filter", "peek", "anyMatch", "allMatch", "noneMatch",
            "mapToInt", "mapToLong", "mapToDouble", "mapToObj", "removeIf", "replaceAll", "computeIfAbsent");

    @Override
    public String code() {
        return RuleCodes.CHECK_REPOSITORY_CALL_IN_LOOP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет обращения к репозиторию в цикле и в поэлементных операциях стримов";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getScope().map(MethodCalls::receiverName).filter(this::isRepository).isPresent())
                .filter(this::isRepeated)
                .map(call -> violation(sourceFile, call,
                        "Обращение к репозиторию '" + call.getScope().get() + "." + call.getNameAsString()
                                + "' в цикле: на каждый элемент уходит отдельный запрос к БД (проблема N+1);"
                                + " загрузите или сохраните данные одним запросом: findAllById, saveAll,"
                                + " запрос с IN"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    // USER_REPOSITORY - константа, а не внедренный бин
    private boolean isRepository(String receiver) {
        return REPOSITORY.matcher(receiver).matches() && !CONSTANT_NAME.matcher(receiver).matches();
    }

    /**
     * @return true, если вызов выполняется на каждой итерации цикла или для каждого элемента стрима
     */
    private boolean isRepeated(MethodCallExpr call) {
        Node child = call;
        Node current = call.getParentNode().orElse(null);
        while (current != null && !(current instanceof BodyDeclaration<?>)) {
            // for (User user : repository.findAll()) - источник цикла вычисляется один раз
            if (current instanceof ForEachStmt loop && loop.getBody() == child) {
                return true;
            }
            // Инициализация for выполняется один раз, условие, шаг и тело - на каждой итерации
            Node part = child;
            if (current instanceof ForStmt loop && loop.getInitialization().stream().noneMatch(init -> init == part)) {
                return true;
            }
            if (current instanceof WhileStmt || current instanceof DoStmt) {
                return true;
            }
            if (current instanceof LambdaExpr lambda && isIteratingLambda(lambda)) {
                return true;
            }
            child = current;
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    // items.forEach(item -> ...), stream.map(item -> ...)
    private boolean isIteratingLambda(LambdaExpr lambda) {
        return lambda.getParentNode()
                .filter(parent -> parent instanceof MethodCallExpr)
                .map(parent -> (MethodCallExpr) parent)
                .filter(call -> ITERATING_METHODS.contains(call.getNameAsString()))
                .isPresent();
    }
}
