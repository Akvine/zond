package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.Mappings;
import ru.akvine.zond.rules.support.Modules;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class DuplicateEndpointRule extends AbstractRule implements ProjectRule {
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[^/}]*}");
    private static final Pattern SLASHES = Pattern.compile("/+");
    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of("Controller", "RestController");

    // Обработчики с одним адресом могут различаться по этим условиям - сравнивать их только по адресу нельзя
    private static final Set<String> NARROWING_MEMBERS = Set.of("params", "headers", "consumes", "produces");
    private static final String SLASH = "/";

    @Override
    public String code() {
        return RuleCodes.DUPLICATE_ENDPOINT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет обработчики запросов с одинаковым адресом и HTTP-методом";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        // "GET /users/{}" -> обработчики с таким адресом
        Map<String, List<Handler>> known = new HashMap<>();
        Modules modules = Modules.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                if (!Annotations.hasAny(type, CONTROLLER_ANNOTATIONS)) {
                    continue;
                }
                List<String> prefixes = Mappings.find(type).map(Mappings::pathKeys).orElse(List.of(""));
                for (MethodDeclaration method : type.getMethods()) {
                    String handler = type.getNameAsString() + "." + method.getNameAsString();
                    // Один и тот же адрес, записанный у метода дважды ({"", "/"}), - не конфликт с самим собой
                    for (String endpoint : new LinkedHashSet<>(endpointsOf(method, prefixes))) {
                        List<Handler> handlers = known.computeIfAbsent(endpoint, key -> new ArrayList<>());
                        // Обработчики разных приложений одного хранилища друг другу не мешают
                        Optional<Handler> first = handlers.stream()
                                .filter(other -> !modules.areSeparate(other.file(), sourceFile.path()))
                                .findFirst();
                        handlers.add(new Handler(handler, sourceFile.path()));
                        if (first.isPresent()) {
                            violations.add(violation(sourceFile, method,
                                    "Обработчик '" + handler + "' отвечает на тот же запрос " + endpoint + ", что и '"
                                            + first.get().name() + "': Spring не сможет выбрать между ними, и приложение"
                                            + " не запустится; оставьте один либо разведите адреса"));
                        }
                    }
                }
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

    /**
     * @param name имя вида Контроллер.метод
     * @param file файл, в котором обработчик объявлен
     */
    private record Handler(String name, Path file) {
    }

    private List<String> endpointsOf(MethodDeclaration method, List<String> prefixes) {
        Optional<AnnotationExpr> mapping = Mappings.find(method);
        List<String> endpoints = new ArrayList<>();
        // Без HTTP-метода обработчик отвечает на все методы сразу - с чем его сравнивать, неясно
        if (mapping.isEmpty()
                || Mappings.httpMethod(mapping.get()).isEmpty()
                || Mappings.hasMember(mapping.get(), NARROWING_MEMBERS)) {
            return endpoints;
        }
        for (String prefix : prefixes) {
            for (String path : Mappings.pathKeys(mapping.get())) {
                endpoints.add(Mappings.httpMethod(mapping.get()) + " " + normalize(prefix + SLASH + path));
            }
        }
        return endpoints;
    }

    // /users//{id}/ и users/{userId} - один и тот же адрес: имя переменной на выбор обработчика не влияет
    private String normalize(String path) {
        String normalized = SLASHES.matcher(PATH_VARIABLE.matcher(SLASH + path).replaceAll("{}")).replaceAll(SLASH);
        return normalized.length() > 1 && normalized.endsWith(SLASH)
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }
}
