package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class CheckNamingConventionRule extends AbstractRule {
    private static final Pattern UPPER_CAMEL_CASE = Pattern.compile("^[A-Z][a-zA-Z0-9]*$");
    private static final Pattern LOWER_CAMEL_CASE = Pattern.compile("^[a-z][a-zA-Z0-9]*$");
    private static final Pattern CONSTANT_CASE = Pattern.compile("^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$");
    private static final Pattern PACKAGE = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$");

    // static final, но по общепринятой практике пишутся не как константы
    private static final Set<String> CONSTANT_EXCEPTIONS = Set.of("serialVersionUID", "log", "logger");

    // Безымянная переменная
    private static final String UNNAMED = "_";

    @Override
    public String code() {
        return RuleCodes.CHECK_NAMING_CONVENTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет имена, нарушающие соглашения Java: классы, методы, поля, переменные, пакеты";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        CompilationUnit unit = sourceFile.unit();
        List<Violation> violations = new ArrayList<>();

        unit.getPackageDeclaration()
                .filter(declaration -> !PACKAGE.matcher(declaration.getNameAsString()).matches())
                .ifPresent(declaration -> violations.add(violation(sourceFile, declaration,
                        "Имя пакета '" + declaration.getNameAsString() + "' должно быть в нижнем регистре")));

        for (Node node : unit.findAll(Node.class)) {
            if (node instanceof TypeDeclaration<?> type) {
                check(sourceFile, type, type.getNameAsString(), UPPER_CAMEL_CASE,
                        "Имя типа", "UpperCamelCase", violations);
            } else if (node instanceof EnumConstantDeclaration constant) {
                check(sourceFile, constant, constant.getNameAsString(), CONSTANT_CASE,
                        "Имя константы enum", "UPPER_SNAKE_CASE", violations);
            } else if (node instanceof MethodDeclaration method) {
                // В тестах имена методов с подчеркиваниями - распространенный стиль
                if (!TestClasses.isInside(method)) {
                    check(sourceFile, method, method.getNameAsString(), LOWER_CAMEL_CASE,
                            "Имя метода", "lowerCamelCase", violations);
                }
            } else if (node instanceof Parameter parameter) {
                check(sourceFile, parameter, parameter.getNameAsString(), LOWER_CAMEL_CASE,
                        "Имя параметра", "lowerCamelCase", violations);
            } else if (node instanceof VariableDeclarator variable) {
                checkVariable(sourceFile, variable, violations);
            }
        }

        violations.sort(Comparator.comparingInt(Violation::line));
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.INFO;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    private void checkVariable(SourceFile sourceFile, VariableDeclarator variable, List<Violation> violations) {
        String name = variable.getNameAsString();
        Node parent = variable.getParentNode().orElse(null);

        if (parent instanceof FieldDeclaration field && field.isStatic() && field.isFinal()) {
            if (!CONSTANT_EXCEPTIONS.contains(name)) {
                check(sourceFile, variable, name, CONSTANT_CASE, "Имя константы", "UPPER_SNAKE_CASE", violations);
            }
        } else if (parent instanceof FieldDeclaration) {
            check(sourceFile, variable, name, LOWER_CAMEL_CASE, "Имя поля", "lowerCamelCase", violations);
        } else if (parent instanceof VariableDeclarationExpr) {
            check(sourceFile, variable, name, LOWER_CAMEL_CASE, "Имя переменной", "lowerCamelCase", violations);
        }
    }

    private void check(
            SourceFile sourceFile,
            Node node,
            String name,
            Pattern pattern,
            String subject,
            String style,
            List<Violation> violations) {
        if (!UNNAMED.equals(name) && !pattern.matcher(name).matches()) {
            violations.add(violation(sourceFile, node, subject + " '" + name + "' должно быть в стиле " + style));
        }
    }
}
