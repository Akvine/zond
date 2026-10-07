package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.ProjectClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class OverridableCallInConstructorRule extends AbstractRule implements ProjectRule {
    @Override
    public String code() {
        return RuleCodes.OVERRIDABLE_CALL_IN_CONSTRUCTOR_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет вызов из конструктора метода, который переопределен в наследнике";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                if (type.isInterface() || type.isFinal()) {
                    continue;
                }
                List<ClassOrInterfaceDeclaration> subclasses = classes.subclasses(type);
                if (subclasses.isEmpty()) {
                    continue;
                }
                for (ConstructorDeclaration constructor : type.getConstructors()) {
                    for (MethodCallExpr call : constructor.findAll(MethodCallExpr.class)) {
                        if (!isOwnOverridable(call, type) || isDeferred(call, constructor)) {
                            continue;
                        }
                        findOverride(call, subclasses).ifPresent(subclass -> violations.add(violation(sourceFile, call,
                                "Конструктор '" + type.getNameAsString() + "' вызывает метод '" + call.getNameAsString()
                                        + "', переопределенный в '" + subclass + "': он выполнится раньше, чем"
                                        + " наследник заполнит свои поля, и увидит в них null и нули; сделайте"
                                        + " метод final или private либо вынесите вызов из конструктора")));
                    }
                }
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    // Вызов без объекта либо через this, а сам метод можно переопределить
    private boolean isOwnOverridable(MethodCallExpr call, ClassOrInterfaceDeclaration type) {
        boolean onThis = call.getScope().isEmpty() || call.getScope().get().isThisExpr();
        return onThis && type.getMethodsByName(call.getNameAsString()).stream()
                .filter(method -> method.getParameters().size() == call.getArguments().size())
                .anyMatch(method -> !method.isPrivate() && !method.isFinal() && !method.isStatic());
    }

    // Внутри лямбды и анонимного класса вызов выполняется позже, когда объект уже создан
    private boolean isDeferred(MethodCallExpr call, ConstructorDeclaration constructor) {
        return call.findAncestor(LambdaExpr.class, lambda -> constructor.isAncestorOf(lambda)).isPresent()
                || call.findAncestor(ObjectCreationExpr.class,
                creation -> creation.getAnonymousClassBody().isPresent() && constructor.isAncestorOf(creation)).isPresent();
    }

    private Optional<String> findOverride(MethodCallExpr call, List<ClassOrInterfaceDeclaration> subclasses) {
        for (ClassOrInterfaceDeclaration subclass : subclasses) {
            for (MethodDeclaration method : subclass.getMethodsByName(call.getNameAsString())) {
                if (method.getParameters().size() == call.getArguments().size() && !method.isStatic()) {
                    return Optional.of(subclass.getNameAsString());
                }
            }
        }
        return Optional.empty();
    }
}
