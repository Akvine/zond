package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class UnboundedRequestCollectionRule extends AbstractRule implements ProjectRule {
    private static final String SIZE = "Size";
    private static final Set<String> COLLECTION_TYPES = Set.of("List", "Set", "Collection", "Map", "ArrayList");

    // byte[] и char[] приходят одной строкой, а не набором элементов: их размер ограничен размером запроса
    private static final Set<String> SCALAR_ARRAYS = Set.of("byte[]", "char[]");

    private static final Set<String> CONTROLLERS = Set.of("Controller", "RestController");
    // Параметр обработчика, в который Spring разбирает тело или поля запроса
    private static final Set<String> BINDING_ANNOTATIONS = Set.of("RequestBody", "ModelAttribute", "RequestPart");
    // Сообщение из очереди тоже приходит извне
    private static final Set<String> LISTENER_ANNOTATIONS =
            Set.of("KafkaListener", "RabbitListener", "JmsListener", "SqsListener", "StreamListener");

    // Запасной признак, когда в проверку не попал ни один контроллер: имя класса либо аннотации валидации
    private static final Pattern REQUEST_NAME = Pattern.compile(".*(Request|Form|Command|Payload)$");
    private static final Set<String> VALIDATION_ANNOTATIONS = Set.of(
            "NotNull", "NotBlank", "NotEmpty", "Valid", "Min", "Max", "Pattern", "Positive", "PositiveOrZero",
            "Email", "Size");

    @Override
    public String code() {
        return RuleCodes.UNBOUNDED_REQUEST_COLLECTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет коллекции во входных DTO без ограничения размера (@Size)";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        // Какие классы принимают данные извне, видно по обработчикам запросов и сообщений. Имя класса об этом
        // не говорит: OrderRequest бывает и запросом, который приложение само отправляет другому сервису
        boolean handlersKnown = classes.all().stream().anyMatch(this::isHandler);
        Set<ClassOrInterfaceDeclaration> inbound = handlersKnown ? inboundTypes(classes, true, true) : Set.of();
        // Запрос присылает кто угодно, а сообщение в очередь обычно кладет свой же сервис: уверенности меньше
        Set<ClassOrInterfaceDeclaration> fromRequests = handlersKnown ? inboundTypes(classes, true, false) : Set.of();

        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                boolean accepts = handlersKnown ? inbound.contains(type) : looksLikeRequest(type);
                if (type.isInterface() || !accepts) {
                    continue;
                }
                for (FieldDeclaration field : type.getFields()) {
                    if (isCollection(field) && !field.isStatic() && !Annotations.has(field, SIZE)) {
                        violations.add(violation(sourceFile, field,
                                "Коллекция '" + fieldNames(field) + "' во входном DTO '" + type.getNameAsString()
                                        + "' без @Size: клиент может прислать сколько угодно элементов и исчерпать"
                                        + " память или процессор; ограничьте размер: @Size(max = ...)")
                                .withConfidence(fromRequests.contains(type) ? Confidence.PROBABLE : Confidence.SUSPICION));
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
        return ErrorType.SECURITY;
    }

    private boolean isHandler(ClassOrInterfaceDeclaration type) {
        return !TestClasses.isInside(type) && (Annotations.hasAny(type, CONTROLLERS)
                || type.getMethods().stream().anyMatch(method -> Annotations.hasAny(method, LISTENER_ANNOTATIONS)));
    }

    // Классы, в которые разбирается запрос или сообщение, вместе с классами их полей и предками
    private Set<ClassOrInterfaceDeclaration> inboundTypes(ProjectClasses classes, boolean requests, boolean messages) {
        Deque<ClassOrInterfaceDeclaration> queue = new ArrayDeque<>();
        for (ClassOrInterfaceDeclaration type : classes.all()) {
            if (TestClasses.isInside(type)) {
                continue;
            }
            if (requests && Annotations.hasAny(type, CONTROLLERS)) {
                // Аннотации параметров могут стоять и в интерфейсе, который контроллер реализует
                List<ClassOrInterfaceDeclaration> declarations = new ArrayList<>(List.of(type));
                type.getImplementedTypes().forEach(implemented -> classes.resolve(implemented)
                        .filter(ClassOrInterfaceDeclaration::isInterface)
                        .ifPresent(declarations::add));
                declarations.stream()
                        .flatMap(declaration -> declaration.getMethods().stream())
                        .flatMap(method -> method.getParameters().stream())
                        .filter(parameter -> Annotations.hasAny(parameter, BINDING_ANNOTATIONS))
                        .forEach(parameter -> collect(parameter.getType(), classes, queue));
            }
            for (MethodDeclaration method : type.getMethods()) {
                if (messages && Annotations.hasAny(method, LISTENER_ANNOTATIONS)) {
                    for (Parameter parameter : method.getParameters()) {
                        collect(parameter.getType(), classes, queue);
                    }
                }
            }
        }

        Set<ClassOrInterfaceDeclaration> inbound = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            ClassOrInterfaceDeclaration type = queue.poll();
            if (!inbound.add(type)) {
                continue;
            }
            classes.parent(type).ifPresent(queue::add);
            type.getFields().stream()
                    .filter(field -> !field.isStatic())
                    .forEach(field -> collect(field.getElementType(), classes, queue));
        }
        return inbound;
    }

    // Сам тип и все, что стоит в его угловых скобках: List<OrderLine> -> OrderLine
    private void collect(Type type, ProjectClasses classes, Deque<ClassOrInterfaceDeclaration> queue) {
        if (type.isArrayType()) {
            collect(type.asArrayType().getComponentType(), classes, queue);
            return;
        }
        if (!type.isClassOrInterfaceType()) {
            return;
        }
        // Тип ищется с учетом импортов: OrderRequest бывает и свой, и из клиента другого сервиса,
        // а Update из библиотеки - не одноименный класс проекта
        classes.resolve(type.asClassOrInterfaceType())
                .filter(found -> !found.isInterface())
                .ifPresent(queue::add);
        type.asClassOrInterfaceType().getTypeArguments()
                .ifPresent(arguments -> arguments.forEach(argument -> collect(argument, classes, queue)));
    }

    private boolean looksLikeRequest(ClassOrInterfaceDeclaration type) {
        return REQUEST_NAME.matcher(type.getNameAsString()).matches()
                || type.getFields().stream().anyMatch(field -> Annotations.hasAny(field, VALIDATION_ANNOTATIONS));
    }

    private boolean isCollection(FieldDeclaration field) {
        return field.getVariables().stream()
                .anyMatch(variable -> COLLECTION_TYPES.contains(LocalTypes.typeName(variable.getType()))
                        || variable.getType().isArrayType() && !SCALAR_ARRAYS.contains(variable.getType().asString()));
    }

    private String fieldNames(FieldDeclaration field) {
        return field.getVariables().stream()
                .map(NodeWithSimpleName::getNameAsString)
                .collect(Collectors.joining(", "));
    }
}
