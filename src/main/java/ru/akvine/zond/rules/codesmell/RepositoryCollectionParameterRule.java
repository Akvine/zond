package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Queries;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class RepositoryCollectionParameterRule extends AbstractRule {
    private static final Set<String> CONCRETE_COLLECTIONS =
            Set.of("List", "Set", "ArrayList", "LinkedList", "HashSet", "LinkedHashSet", "TreeSet");

    @Override
    public String code() {
        return RuleCodes.REPOSITORY_COLLECTION_PARAMETER_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет методы репозиториев, которые принимают List или Set вместо Collection";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (!Queries.isRepositoryInterface(type)) {
                continue;
            }
            for (MethodDeclaration method : type.getMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    String parameterType = LocalTypes.typeName(parameter.getType());
                    if (CONCRETE_COLLECTIONS.contains(parameterType)) {
                        violations.add(violation(sourceFile, parameter,
                                "Параметр '" + parameter.getNameAsString() + "' метода '" + method.getNameAsString()
                                        + "' объявлен как " + parameterType + ": запросу порядок и уникальность"
                                        + " значений не важны, а вызывающему придется перекладывать свою"
                                        + " коллекцию в " + parameterType + "; объявите параметр как Collection"));
                    }
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
}
