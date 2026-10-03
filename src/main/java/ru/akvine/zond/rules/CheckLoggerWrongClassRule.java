package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class CheckLoggerWrongClassRule extends AbstractRule {
    private static final String GET_LOGGER = "getLogger";
    private static final Set<String> LOGGER_FACTORIES = Set.of("LoggerFactory", "LogManager", "Logger");

    @Override
    public String code() {
        return RuleCodes.CHECK_LOGGER_WRONG_CLASS_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет логгер, созданный с именем чужого класса";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            boolean createsLogger = GET_LOGGER.equals(call.getNameAsString())
                    && call.getScope().map(MethodCalls::receiverName).filter(LOGGER_FACTORIES::contains).isPresent();
            if (!createsLogger) {
                continue;
            }

            // LoggerFactory.getLogger(Other.class), Logger.getLogger(Other.class.getName())
            Set<String> enclosing = enclosingTypeNames(call);
            for (ClassExpr classExpr : call.findAll(ClassExpr.class)) {
                String loggerClass = LocalTypes.typeName(classExpr.getType());
                if (!enclosing.isEmpty() && !enclosing.contains(loggerClass)) {
                    violations.add(violation(sourceFile, call,
                            "Логгер создан с именем класса '" + loggerClass + "', а находится в другом классе -"
                                    + " обычно это след копирования: записи будут приписаны чужому классу, и"
                                    + " настройка уровня логирования для этого класса на них не подействует"));
                }
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

    // Сам класс и все внешние по отношению к нему
    private Set<String> enclosingTypeNames(Node node) {
        Set<String> names = new HashSet<>();
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof TypeDeclaration<?> type) {
                names.add(type.getNameAsString());
            }
            current = current.getParentNode().orElse(null);
        }
        return names;
    }
}
