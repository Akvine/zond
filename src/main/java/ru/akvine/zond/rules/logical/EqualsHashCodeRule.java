package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class EqualsHashCodeRule extends AbstractRule {
    // Lombok и AutoValue сгенерируют оба метода сами
    private static final Set<String> GENERATING_ANNOTATIONS =
            Set.of("EqualsAndHashCode", "Data", "Value", "AutoValue", "AutoOneOf");

    @Override
    public String code() {
        return RuleCodes.EQUALS_HASH_CODE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет классы, где переопределен только один из методов equals и hashCode";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (type.isInterface()
                    || TestClasses.annotationNames(type).stream().anyMatch(GENERATING_ANNOTATIONS::contains)) {
                continue;
            }

            Optional<MethodDeclaration> equals = findMethod(type, "equals", 1);
            Optional<MethodDeclaration> hashCode = findMethod(type, "hashCode", 0);

            if (equals.isPresent() && hashCode.isEmpty()) {
                violations.add(violation(sourceFile, equals.get(),
                        "В классе '" + type.getNameAsString() + "' переопределен equals без hashCode:"
                                + " равные объекты попадут в разные корзины HashMap / HashSet"));
            }
            // hashCode без equals договор HashMap не нарушает: равные по ссылке объекты и так дают один хэш.
            // У наследника (FunctionType extends ... JSType) equals обычно объявлен у предка
            if (hashCode.isPresent() && equals.isEmpty() && type.getExtendedTypes().isEmpty()) {
                violations.add(violation(sourceFile, hashCode.get(),
                        "В классе '" + type.getNameAsString() + "' переопределен hashCode без equals:"
                                + " объекты с одинаковым хэшем не будут считаться равными")
                        .withLevel(ErrorLevel.MINOR));
            }
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

    private Optional<MethodDeclaration> findMethod(ClassOrInterfaceDeclaration type, String name, int parameters) {
        return type.getMethodsByName(name).stream()
                .filter(method -> method.getParameters().size() == parameters)
                // @Memoized public abstract int hashCode(): объявление без тела ничего не переопределяет
                .filter(method -> method.getBody().isPresent())
                .findFirst();
    }
}
