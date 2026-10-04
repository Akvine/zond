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
import ru.akvine.zond.rules.support.LocalTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class CheckEqualsWrongSignatureRule extends AbstractRule {
    private static final String EQUALS = "equals";
    private static final String OBJECT = "Object";

    // Опечатка в имени: метод не переопределяет метод Object, а объявляет новый
    private static final Map<String, String> MISSPELLED = Map.of(
            "hashcode", "hashCode",
            "tostring", "toString",
            "equal", "equals",
            "compareto", "compareTo");

    @Override
    public String code() {
        return RuleCodes.CHECK_EQUALS_WRONG_SIGNATURE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет equals, hashCode и toString с неверной сигнатурой или опечаткой в имени";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            boolean overridesEquals = type.getMethodsByName(EQUALS).stream().anyMatch(this::takesObject);

            for (MethodDeclaration method : type.getMethods()) {
                String name = method.getNameAsString();

                // equals(Order other) рядом с настоящим equals(Object) - допустимая перегрузка
                if (EQUALS.equals(name) && method.getParameters().size() == 1 && !takesObject(method) && !overridesEquals) {
                    violations.add(violation(sourceFile, method,
                            "equals(" + method.getParameter(0).getType() + ") не переопределяет equals(Object):"
                                    + " коллекции и HashMap вызывают именно equals(Object) и будут сравнивать"
                                    + " объекты по ссылке; объявите параметр типа Object"));
                }

                String expected = MISSPELLED.get(name);
                if (expected != null && !name.equals(expected)) {
                    violations.add(violation(sourceFile, method,
                            "Метод '" + name + "' вместо '" + expected + "': из-за опечатки это новый метод,"
                                    + " а не переопределение, вызываться он не будет; исправьте имя и добавьте"
                                    + " @Override"));
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

    private boolean takesObject(MethodDeclaration method) {
        return method.getParameters().size() == 1
                && OBJECT.equals(LocalTypes.typeName(method.getParameter(0).getType()));
    }
}
