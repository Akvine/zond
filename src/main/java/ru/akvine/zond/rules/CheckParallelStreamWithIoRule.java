package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class CheckParallelStreamWithIoRule extends AbstractRule {
    private static final Set<String> PARALLEL_METHODS = Set.of("parallelStream", "parallel");

    // Типы не разрешаем, поэтому ввод-вывод узнаем по именам: получателя вызова, метода или создаваемого класса
    private static final String FILES = "Files";
    private static final Pattern IO_RECEIVER = Pattern.compile(
            ".*(repository|repo|dao|client|template|connection|socket|channel)$|.*(http|jdbc).*",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> IO_METHODS = Set.of(
            "getForObject", "postForObject", "getForEntity", "postForEntity", "exchange",
            "executeQuery", "executeUpdate", "prepareStatement", "openStream", "openConnection",
            "readAllBytes", "readAllLines", "readString", "writeString", "sleep");

    private static final Set<String> IO_TYPES = Set.of(
            "FileInputStream", "FileOutputStream", "FileReader", "FileWriter", "RandomAccessFile", "URL", "Socket");

    @Override
    public String code() {
        return RuleCodes.CHECK_PARALLEL_STREAM_WITH_IO_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет блокирующий ввод-вывод внутри параллельных стримов";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr parallel : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!PARALLEL_METHODS.contains(parallel.getNameAsString()) || !parallel.getArguments().isEmpty()) {
                continue;
            }

            // Смотрим лямбды и ссылки на методы во всех операциях после parallelStream()
            StreamChains.callsAfter(parallel).stream()
                    .flatMap(operation -> operation.getArguments().stream())
                    .map(this::findIo)
                    .flatMap(Optional::stream)
                    .findFirst()
                    .ifPresent(io -> violations.add(violation(sourceFile, parallel,
                            "Ввод-вывод '" + io + "' в параллельном стриме: блокирующие операции занимают потоки"
                                    + " общего ForkJoinPool, от которого зависят все параллельные стримы"
                                    + " приложения; используйте обычный стрим или отдельный пул потоков")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.STREAM;
    }

    /**
     * @return текст первой найденной операции ввода-вывода в аргументе
     */
    private Optional<String> findIo(Expression argument) {
        List<Node> nodes = new ArrayList<>(argument.findAll(Node.class));
        for (Node node : nodes) {
            if (node instanceof MethodCallExpr call && isIoCall(call)) {
                return Optional.of(call.getScope().map(scope -> scope + ".").orElse("") + call.getNameAsString());
            }
            if (node instanceof MethodReferenceExpr reference && isIoReference(reference)) {
                return Optional.of(reference.toString());
            }
            if (node instanceof ObjectCreationExpr creation && IO_TYPES.contains(creation.getType().getNameAsString())) {
                return Optional.of("new " + creation.getType().getNameAsString());
            }
        }
        return Optional.empty();
    }

    private boolean isIoCall(MethodCallExpr call) {
        return IO_METHODS.contains(call.getNameAsString())
                || call.getScope().filter(this::isIoReceiver).isPresent();
    }

    // Files::readAllBytes, repository::findById
    private boolean isIoReference(MethodReferenceExpr reference) {
        return IO_METHODS.contains(reference.getIdentifier()) || isIoReceiver(reference.getScope());
    }

    // Именно класс Files, а не переменная files
    private boolean isIoReceiver(Expression scope) {
        String name = lastName(scope);
        return FILES.equals(name) || IO_RECEIVER.matcher(name).matches();
    }

    // this.userRepository -> userRepository, getClient() -> getClient
    private String lastName(Expression scope) {
        Expression value = Nodes.unwrap(scope);
        if (value.isFieldAccessExpr()) {
            return value.asFieldAccessExpr().getNameAsString();
        }
        if (value.isMethodCallExpr()) {
            return value.asMethodCallExpr().getNameAsString();
        }
        if (value.isTypeExpr()) {
            return LocalTypes.typeName(value.asTypeExpr().getType());
        }
        return value.toString();
    }
}
