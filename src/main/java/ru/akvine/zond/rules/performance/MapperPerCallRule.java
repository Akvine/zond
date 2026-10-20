package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class MapperPerCallRule extends AbstractRule {
    private static final Set<String> HEAVY_TYPES = Set.of("ObjectMapper", "JsonMapper", "XmlMapper", "Gson", "Yaml");
    // JAXBContext.newInstance(Order.class) разбирает классы и строит модель привязки - сотни миллисекунд;
    // фабрики XML ищут реализацию по classpath через ServiceLoader
    private static final Set<String> JAXB_CONTEXT = Set.of("JAXBContext");
    private static final Set<String> XML_FACTORIES = Set.of(
            "DocumentBuilderFactory", "TransformerFactory", "XPathFactory", "SAXParserFactory", "XMLInputFactory",
            "XMLOutputFactory", "SchemaFactory");
    private static final Set<String> FACTORY_METHODS = Set.of("newInstance", "newFactory", "newDefaultInstance", "newDefaultFactory");
    private static final String BEAN = "Bean";
    private static final Set<String> CONFIGURATION_ANNOTATIONS = Set.of("Configuration", "TestConfiguration");

    @Override
    public String code() {
        return RuleCodes.MAPPER_PER_CALL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет ObjectMapper, Gson, JAXBContext, фабрики XML и подобные объекты, которые"
                + " создаются при каждом вызове метода";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>(sourceFile.unit().findAll(ObjectCreationExpr.class).stream()
                .filter(creation -> HEAVY_TYPES.contains(creation.getType().getNameAsString()))
                .filter(creation -> isCreatedOnEveryCall(creation) && !TestClasses.isInside(creation))
                .map(creation -> violation(sourceFile, creation,
                        "'" + creation + "' создается при каждом вызове метода: объект дорог в создании"
                                + " и потокобезопасен после настройки; создайте его один раз - полем, константой"
                                + " или бином"))
                .toList());
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!FACTORY_METHODS.contains(call.getNameAsString()) || call.getScope().isEmpty()
                    || !isCreatedOnEveryCall(call) || TestClasses.isInside(call)) {
                continue;
            }
            String owner = call.getScope().get().toString();
            if (JAXB_CONTEXT.contains(owner)) {
                violations.add(violation(sourceFile, call, "'" + call + "' создается при каждом вызове метода:"
                        + " контекст JAXB разбирает классы и строит модель привязки - это сотни миллисекунд, а при"
                        + " частых вызовах еще и рост metaspace; контекст потокобезопасен - создайте его один раз"
                        + " константой или полем, а Marshaller / Unmarshaller берите от него на каждый вызов"));
            } else if (XML_FACTORIES.contains(owner)) {
                violations.add(violation(sourceFile, call, "'" + call + "' создается при каждом вызове метода:"
                        + " фабрика ищет реализацию по classpath через ServiceLoader; создайте и настройте ее один раз"
                        + " полем (сама фабрика не потокобезопасна - берите от нее парсер под синхронизацией"
                        + " либо храните фабрику в ThreadLocal)"));
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
        return ErrorType.PERFORMANCE;
    }

    // Внутри обычного метода; поле, конструктор, @Bean-метод и класс настроек - как раз места для одного экземпляра
    private boolean isCreatedOnEveryCall(Node creation) {
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
