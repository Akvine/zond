package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckMapperPerCallRule extends AbstractRule {
    private static final Set<String> HEAVY_TYPES = Set.of("ObjectMapper", "JsonMapper", "XmlMapper", "Gson", "Yaml");
    private static final String BEAN = "Bean";
    private static final Set<String> CONFIGURATION_ANNOTATIONS = Set.of("Configuration", "TestConfiguration");

    @Override
    public String code() {
        return RuleCodes.CHECK_MAPPER_PER_CALL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет ObjectMapper, Gson и подобные объекты, которые создаются при каждом вызове метода";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(ObjectCreationExpr.class).stream()
                .filter(creation -> HEAVY_TYPES.contains(creation.getType().getNameAsString()))
                .filter(creation -> isCreatedOnEveryCall(creation) && !TestClasses.isInside(creation))
                .map(creation -> violation(sourceFile, creation,
                        "'" + creation + "' создается при каждом вызове метода: объект дорог в создании"
                                + " и потокобезопасен после настройки; создайте его один раз - полем, константой"
                                + " или бином"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    // Внутри обычного метода; поле, конструктор, @Bean-метод и класс настроек - как раз места для одного экземпляра
    private boolean isCreatedOnEveryCall(ObjectCreationExpr creation) {
        Optional<Node> callable = Nodes.enclosingCallable(creation);
        if (callable.isEmpty() || !(callable.get() instanceof MethodDeclaration method)) {
            return false;
        }
        boolean inConfiguration = method.getParentNode()
                .filter(parent -> parent instanceof TypeDeclaration<?> type
                        && Annotations.hasAny(type, CONFIGURATION_ANNOTATIONS))
                .isPresent();
        return !Annotations.has(method, BEAN) && !inConfiguration && !method.isStatic();
    }
}
