package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class CheckPublicStaticNonFinalFieldRule extends AbstractRule {

    @Override
    public String code() {
        return RuleCodes.CHECK_PUBLIC_STATIC_NON_FINAL_FIELD_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет public static поля без final";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // Поля интерфейса неявно final, а без модификатора public они сюда и не попадут
        return sourceFile.unit().findAll(FieldDeclaration.class).stream()
                .filter(field -> field.isPublic() && field.isStatic() && !field.isFinal())
                .map(field -> violation(sourceFile, field,
                        "public static поле '" + fieldNames(field) + "' без final: изменить его может любой код"
                                + " из любого потока; сделайте поле final или закройте доступ"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    // В одном объявлении может быть несколько полей: static int a, b;
    private String fieldNames(FieldDeclaration field) {
        return field.getVariables().stream()
                .map(NodeWithSimpleName::getNameAsString)
                .collect(Collectors.joining(", "));
    }
}
