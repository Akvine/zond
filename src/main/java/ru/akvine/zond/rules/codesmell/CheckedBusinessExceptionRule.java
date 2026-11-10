package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.HttpCalls;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Свое проверяемое исключение, которое бросают только из бизнес-кода. Проверяемое исключение заставляет каждого
 * вызывающего решить, что делать с отказом; это оправдано на границе с внешней системой - сбой чужого сервиса,
 * IOException библиотеки. Отказ по правилам предметной области доходит до общего обработчика как есть,
 * и throws по всей цепочке вызовов ничего не дает.
 */
@Component
public class CheckedBusinessExceptionRule extends AbstractRule implements ProjectRule {
    private static final String EXCEPTION = "Exception";
    // Исключения внешнего мира: ввод-вывод, сеть, чужие сервисы, разбор чужих данных
    private static final Pattern BOUNDARY_FAILURE = Pattern.compile(
            ".*(IO|SQL|Sql|Timeout|Interrupted|Execution|URISyntax|Security|Json|Messaging|JMS|Jms|Http|Rest|Feign"
                    + "|WebClient|Remote|Connect|Socket|Mail|Parse|JAXB|SAX|XML|Transformer|ReflectiveOperation"
                    + "|ClassNotFound|Client)\\w*Exception$");
    // Классы, которые сами ходят во внешние системы
    private static final Pattern BOUNDARY_CLASS = Pattern.compile(
            "(?i).*(client|gateway|adapter|connector|integration|proxy|provider|sender|publisher|api|storage"
                    + "|importer|exporter|reader|writer|parser|loader)$");
    // В сообщении называем пару мест, остальные - числом
    private static final int MAX_PLACES = 2;

    @Override
    public String code() {
        return RuleCodes.CHECKED_BUSINESS_EXCEPTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет свои проверяемые исключения, которые бросают только из бизнес-кода,"
                + " а не на границе с внешней системой";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        // class OrderException extends Exception: наследники RuntimeException и IOException сюда не входят
        List<ClassOrInterfaceDeclaration> checked = classes.all().stream()
                .filter(type -> !type.isInterface() && type.getExtendedTypes().stream()
                        .anyMatch(parent -> EXCEPTION.equals(parent.getNameAsString())))
                .toList();
        if (checked.isEmpty()) {
            return List.of();
        }

        // Имя исключения -> места в коде приложения, где его создают
        Set<String> names = new HashSet<>();
        Map<ClassOrInterfaceDeclaration, Set<String>> families = new HashMap<>();
        for (ClassOrInterfaceDeclaration type : checked) {
            Set<String> family = classes.subclasses(type).stream()
                    .map(ClassOrInterfaceDeclaration::getNameAsString)
                    .collect(Collectors.toCollection(HashSet::new));
            family.add(type.getNameAsString());
            families.put(type, family);
            names.addAll(family);
        }
        Map<String, List<ObjectCreationExpr>> created = new HashMap<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ObjectCreationExpr creation : sourceFile.unit().findAll(ObjectCreationExpr.class)) {
                String name = creation.getType().getNameAsString();
                if (names.contains(name) && !TestClasses.isInside(creation)) {
                    created.computeIfAbsent(name, key -> new ArrayList<>()).add(creation);
                }
            }
        }

        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : checked) {
            List<ObjectCreationExpr> places = families.get(type).stream()
                    .flatMap(name -> created.getOrDefault(name, List.of()).stream())
                    .toList();
            // Исключение, которое нигде не создают, бросает кто-то другой: судить не о чем
            if (places.isEmpty() || places.stream().anyMatch(this::isAtBoundary)) {
                continue;
            }
            violations.add(violation(classes.fileOf(type), type, message(type, places)));
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

    // Что считать бизнес-кодом, решают имена и вызовы вокруг: это суждение, а не факт
    @Override
    public Confidence confidence() {
        return Confidence.PROBABLE;
    }

    /**
     * Исключение создают на границе с внешней системой: в catch вокруг ее сбоя, в методе с HTTP-вызовом
     * либо в классе, который с ней работает
     */
    private boolean isAtBoundary(ObjectCreationExpr creation) {
        boolean translatesFailure = creation.findAncestor(CatchClause.class)
                .filter(this::catchesBoundaryFailure)
                .isPresent();
        boolean boundaryClass = creation.findAncestor(ClassOrInterfaceDeclaration.class)
                .filter(owner -> BOUNDARY_CLASS.matcher(owner.getNameAsString()).matches())
                .isPresent();
        boolean callsOut = creation.findAncestor(MethodDeclaration.class)
                .filter(method -> method.findAll(MethodCallExpr.class).stream()
                        .anyMatch(call -> HttpCalls.describe(call).isPresent()))
                .isPresent();
        return translatesFailure || boundaryClass || callsOut;
    }

    private boolean catchesBoundaryFailure(CatchClause clause) {
        Type type = clause.getParameter().getType();
        List<? extends Type> types = type.isUnionType() ? type.asUnionType().getElements() : List.of(type);
        return types.stream().map(LocalTypes::typeName).anyMatch(name -> BOUNDARY_FAILURE.matcher(name).matches());
    }

    private String message(ClassOrInterfaceDeclaration type, List<ObjectCreationExpr> places) {
        String examples = places.stream()
                .limit(MAX_PLACES)
                .map(this::place)
                .collect(Collectors.joining(", "));
        String rest = places.size() > MAX_PLACES ? " и еще " + (places.size() - MAX_PLACES) : "";
        return "Проверяемое исключение '" + type.getNameAsString() + "' бросают только из бизнес-кода (" + examples
                + rest + "), а не на границе с внешней системой: оно заставляет писать throws или try-catch по всей"
                + " цепочке вызовов, а @Transactional без rollbackFor на нем транзакцию не откатывает. Проверяемые"
                + " исключения оправданы там, где вызывающий обязан принять решение, - сбой внешнего сервиса,"
                + " IOException библиотеки; отказ по правилам предметной области наследуйте от RuntimeException";
    }

    // OrderService.place:42
    private String place(Node creation) {
        String owner = creation.findAncestor(ClassOrInterfaceDeclaration.class)
                .map(ClassOrInterfaceDeclaration::getNameAsString)
                .orElse("");
        String method = creation.findAncestor(MethodDeclaration.class)
                .map(found -> "." + found.getNameAsString())
                .orElse("");
        return owner + method + ":" + creation.getBegin().map(position -> position.line).orElse(0);
    }
}
