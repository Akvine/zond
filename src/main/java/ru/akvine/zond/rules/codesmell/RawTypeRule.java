package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class RawTypeRule extends AbstractRule {
    private static final String JDK_PACKAGE = "java.";
    private static final Set<String> GENERIC_TYPES = Set.of(
            "List", "ArrayList", "LinkedList", "Set", "HashSet", "LinkedHashSet", "TreeSet", "Map", "HashMap",
            "LinkedHashMap", "TreeMap", "ConcurrentHashMap", "Collection", "Iterable", "Iterator", "Queue", "Deque",
            "ArrayDeque", "Optional", "Comparator", "Supplier", "Consumer", "Function", "Predicate", "Stream",
            "CompletableFuture", "Future", "Callable");

    @Override
    public String code() {
        return RuleCodes.RAW_TYPE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет обобщенные типы без параметра: List вместо List<String>";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        // List<Map> list = new ArrayList(): на строке два сырых типа, сообщаем один раз
        Set<Integer> reportedLines = new HashSet<>();
        for (ClassOrInterfaceType type : sourceFile.unit().findAll(ClassOrInterfaceType.class)) {
            // new ArrayList<>() - параметр выведет компилятор: аргументы заданы, хоть и пустые
            if (!GENERIC_TYPES.contains(type.getNameAsString()) || type.getTypeArguments().isPresent()
                    || !isTypeUsage(type) || isAnotherClass(sourceFile, type.getNameAsString())) {
                continue;
            }
            if (reportedLines.add(type.getBegin().map(position -> position.line).orElse(0))) {
                violations.add(violation(sourceFile, type,
                        "'" + type.getNameAsString() + "' без параметра типа: компилятор перестает проверять,"
                                + " что в него кладут и что достают, и ошибка типа вылезет при выполнении как"
                                + " ClassCastException; укажите параметр: " + type.getNameAsString() + "<...>"));
            }
        }
        return violations;
    }

    // Queue из org.springframework.amqp.core - это не java.util.Queue, и параметра типа у него нет. Тип из JDK
    // виден в файле, только если импортирован оттуда: по имени либо через import java.util.*
    private boolean isAnotherClass(SourceFile sourceFile, String name) {
        List<ImportDeclaration> imports = sourceFile.unit().getImports().stream()
                .filter(declaration -> !declaration.isStatic())
                .toList();
        // Файл без импортов - отрывок кода: судить не по чему, считаем тип обычным
        if (imports.isEmpty()) {
            return false;
        }
        return imports.stream().noneMatch(declaration -> declaration.getNameAsString().startsWith(JDK_PACKAGE)
                && (declaration.isAsterisk() || declaration.getNameAsString().endsWith("." + name)));
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    // Тип переменной, поля, параметра, результата, создаваемого объекта, приведения либо параметр другого типа.
    // List.class, x instanceof List и Map в Map.Entry сюда не попадают
    private boolean isTypeUsage(ClassOrInterfaceType type) {
        Node parent = type.getParentNode().orElse(null);
        if (parent instanceof ClassOrInterfaceType outer) {
            return outer.getTypeArguments().filter(arguments -> arguments.contains(type)).isPresent();
        }
        return parent instanceof VariableDeclarator
                || parent instanceof Parameter
                || parent instanceof MethodDeclaration
                || parent instanceof ObjectCreationExpr
                || parent instanceof CastExpr;
    }
}
