package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SimpleName;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class UnusedCodeRule extends AbstractRule {
    private static final String SERIAL_VERSION_UID = "serialVersionUID";

    // Эти методы вызывает механизм сериализации, а не код
    private static final Set<String> SERIALIZATION_METHODS =
            Set.of("readObject", "writeObject", "readResolve", "writeReplace", "readObjectNoData");

    // Lombok генерирует код, который использует поля: геттеры, конструкторы, toString и т.п.
    private static final Set<String> LOMBOK_ANNOTATIONS = Set.of(
            "Data", "Value", "Getter", "Setter", "Builder", "SuperBuilder", "With", "ToString", "EqualsAndHashCode",
            "AllArgsConstructor", "RequiredArgsConstructor");

    @Override
    public String code() {
        return RuleCodes.UNUSED_CODE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет неиспользуемые приватные поля и методы, параметры приватных методов и импорты";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        CompilationUnit unit = sourceFile.unit();
        List<Violation> violations = new ArrayList<>();

        Set<String> usedMethods = new HashSet<>();
        unit.findAll(MethodCallExpr.class).forEach(call -> usedMethods.add(call.getNameAsString()));
        unit.findAll(MethodReferenceExpr.class).forEach(reference -> usedMethods.add(reference.getIdentifier()));

        Set<String> usedVariables = new HashSet<>();
        unit.findAll(NameExpr.class).forEach(name -> usedVariables.add(name.getNameAsString()));
        unit.findAll(FieldAccessExpr.class).forEach(access -> usedVariables.add(access.getNameAsString()));
        usedVariables.addAll(methodReferenceScopes(unit));

        for (ImportDeclaration importDeclaration : unit.getImports()) {
            if (!importDeclaration.isAsterisk() && !isImportUsed(unit, importDeclaration)) {
                violations.add(violation(sourceFile, importDeclaration,
                        "Неиспользуемый импорт '" + importDeclaration.getNameAsString() + "'"));
            }
        }

        // Аннотация на поле или методе означает, что им пользуется фреймворк, а не код
        for (FieldDeclaration field : unit.findAll(FieldDeclaration.class)) {
            if (!field.isPrivate() || !field.getAnnotations().isEmpty() || isInsideLombokClass(field)) {
                continue;
            }
            for (VariableDeclarator variable : field.getVariables()) {
                String name = variable.getNameAsString();
                if (!SERIAL_VERSION_UID.equals(name) && !usedVariables.contains(name)) {
                    violations.add(violation(sourceFile, variable, "Неиспользуемое приватное поле '" + name + "'"));
                }
            }
        }

        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            if (!method.isPrivate()
                    || !method.getAnnotations().isEmpty()
                    || SERIALIZATION_METHODS.contains(method.getNameAsString())) {
                continue;
            }

            if (!usedMethods.contains(method.getNameAsString())) {
                violations.add(violation(sourceFile, method,
                        "Неиспользуемый приватный метод '" + method.getNameAsString() + "'"));
                continue;
            }
            reportUnusedParameters(sourceFile, method, violations);
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

    // Только приватные методы: у остальных сигнатуру может диктовать интерфейс или родительский класс
    private void reportUnusedParameters(SourceFile sourceFile, MethodDeclaration method, List<Violation> violations) {
        if (method.getBody().isEmpty()) {
            return;
        }

        Set<String> used = method.getBody().get().findAll(NameExpr.class).stream()
                .map(NameExpr::getNameAsString)
                .collect(Collectors.toSet());
        used.addAll(methodReferenceScopes(method));
        for (Parameter parameter : method.getParameters()) {
            if (!used.contains(parameter.getNameAsString())) {
                violations.add(violation(sourceFile, parameter,
                        "Неиспользуемый параметр '" + parameter.getNameAsString() + "' приватного метода '"
                                + method.getNameAsString() + "'"));
            }
        }
    }

    // NAMES::contains - парсер не знает, переменная это или тип, и хранит NAMES как тип, а не как NameExpr
    private Set<String> methodReferenceScopes(Node root) {
        return root.findAll(MethodReferenceExpr.class).stream()
                .map(MethodReferenceExpr::getScope)
                .filter(Expression::isTypeExpr)
                .map(scope -> LocalTypes.typeName(scope.asTypeExpr().getType()))
                .collect(Collectors.toSet());
    }

    private boolean isInsideLombokClass(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof TypeDeclaration<?> type
                    && TestClasses.annotationNames(type).stream().anyMatch(LOMBOK_ANNOTATIONS::contains)) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    // Импорт используется, если его простое имя встречается в коде либо в комментарии ({@link Type})
    private boolean isImportUsed(CompilationUnit unit, ImportDeclaration importDeclaration) {
        String name = importDeclaration.getName().getIdentifier();

        boolean usedInCode = unit.findAll(SimpleName.class).stream()
                .anyMatch(simpleName -> simpleName.getIdentifier().equals(name));
        if (usedInCode || isUsedAsQualifier(unit, name)) {
            return true;
        }

        Pattern word = Pattern.compile("\\b" + Pattern.quote(name) + "\\b");
        return unit.getAllComments().stream().map(Comment::getContent).anyMatch(text -> word.matcher(text).find());
    }

    // Имена аннотаций и составные имена (Outer.Inner) хранятся как Name, а не SimpleName
    private boolean isUsedAsQualifier(CompilationUnit unit, String name) {
        for (Name qualified : unit.findAll(Name.class)) {
            if (isPartOfHeader(qualified)) {
                continue;
            }
            for (Name part = qualified; part != null; part = part.getQualifier().orElse(null)) {
                if (part.getIdentifier().equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isPartOfHeader(Name name) {
        Node current = name.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof ImportDeclaration || current instanceof PackageDeclaration) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }
}
