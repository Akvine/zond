package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
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
public class EqualsInstanceofInHierarchyRule extends AbstractRule implements ProjectRule {
    private static final String EQUALS = "equals";
    private static final String GET_CLASS = "getClass";

    @Override
    public String code() {
        return RuleCodes.EQUALS_INSTANCEOF_IN_HIERARCHY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет equals через instanceof и у предка, и у наследника с новыми полями: сравнение перестает быть симметричным";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                Optional<ClassOrInterfaceDeclaration> parent = classes.parent(type);
                Optional<MethodDeclaration> own = equalsByInstanceof(type);
                boolean addsFields = type.getFields().stream().anyMatch(field -> !field.isStatic());
                // У абстрактного предка своих объектов нет: сравнивать наследника не с кем
                if (own.isEmpty() || !addsFields || parent.isEmpty() || parent.get().isAbstract()) {
                    continue;
                }
                equalsByInstanceof(parent.get())
                        .filter(inherited -> !inherited.isFinal())
                        .ifPresent(inherited -> violations.add(violation(sourceFile, own.get(),
                                "equals у '" + type.getNameAsString() + "' и у предка '" + parent.get().getNameAsString()
                                        + "' сверяют тип через instanceof, а наследник сравнивает еще и свои поля:"
                                        + " предок.equals(наследник) вернет true, а наследник.equals(предок) - false."
                                        + " Коллекции с такими объектами ведут себя непредсказуемо; сравнивайте"
                                        + " классы через getClass() либо вынесите новые поля в отдельный объект")));
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
        return ErrorType.LOGICAL;
    }

    // equals(Object), в котором тип проверяется через instanceof, а не через getClass()
    private Optional<MethodDeclaration> equalsByInstanceof(ClassOrInterfaceDeclaration type) {
        return type.getMethodsByName(EQUALS).stream()
                .filter(method -> method.getParameters().size() == 1 && method.getBody().isPresent())
                .filter(method -> !method.findAll(InstanceOfExpr.class).isEmpty())
                .filter(method -> method.findAll(MethodCallExpr.class).stream()
                        .noneMatch(call -> GET_CLASS.equals(call.getNameAsString())))
                .findFirst();
    }
}
