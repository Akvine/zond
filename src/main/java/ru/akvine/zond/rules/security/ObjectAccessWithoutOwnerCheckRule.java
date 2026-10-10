package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.SimpleName;
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
import ru.akvine.zond.rules.support.CallGraph;
import ru.akvine.zond.rules.support.Handlers;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.ProjectWords;
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.RepositoryEntities;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Доступ к записи по идентификатору из запроса без проверки, чья она (IDOR). По коду видно только, что
 * проверки рядом нет: она может стоять в фильтре или в запросе - поэтому находка считается подозрением.
 */
@Component
public class ObjectAccessWithoutOwnerCheckRule extends AbstractRule implements ProjectRule {
    // Без аутентификации в проекте нет и пользователей, которым запись могла бы принадлежать
    private static final Set<String> AUTHENTICATION = Set.of(
            "SecurityFilterChain", "WebSecurityConfigurerAdapter", "EnableWebSecurity", "PreAuthorize",
            "SecurityContextHolder", "AuthenticationPrincipal", "EnableMethodSecurity");
    private static final Set<String> CONTROLLERS = Set.of("RestController", "Controller");
    private static final Set<String> ACCESS_ANNOTATIONS = Set.of(
            "PreAuthorize", "PostAuthorize", "PostFilter", "Secured", "RolesAllowed");
    private static final Set<String> REQUEST_VALUES = Set.of("PathVariable", "RequestParam");
    private static final Pattern ID_NAME = Pattern.compile("^id$|.*Id$");

    private static final Set<String> USER_TYPES = Set.of(
            "Principal", "Authentication", "UserDetails", "Jwt", "OAuth2User", "JwtAuthenticationToken",
            "OidcUser", "UsernamePasswordAuthenticationToken");
    // Все, что говорит о проверке прав или о текущем пользователе
    private static final Pattern USER_WORD = Pattern.compile(
            "(?i).*(principal|currentuser|authentication|securitycontext|accessdenied|forbidden|owner|permission"
                    + "|checkaccess|hasaccess|canaccess|authorize).*");

    private static final Set<String> BY_ID_METHODS = Set.of(
            "findById", "getById", "getReferenceById", "getOne", "deleteById", "existsById");
    private static final Set<String> OWNER_FIELDS = Set.of(
            "user", "userid", "owner", "ownerid", "account", "accountid", "customer", "customerid", "client",
            "clientid", "author", "authorid", "member", "memberid", "tenant", "tenantid", "createdby");

    /**
     * @param call   обращение к репозиторию: orderRepository.findById
     * @param entity сущность, которую оно читает или удаляет
     * @param owner  поле сущности, которое говорит о владельце
     */
    private record Access(String call, String entity, String owner) {
    }

    @Override
    public String code() {
        return RuleCodes.OBJECT_ACCESS_WITHOUT_OWNER_CHECK_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет обработчики, которые берут запись по идентификатору из запроса и не проверяют ее владельца";
    }

    @Override
    public Confidence confidence() {
        return Confidence.SUSPICION;
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        List<Violation> violations = new ArrayList<>();
        if (!ProjectWords.hasAny(sourceFiles, AUTHENTICATION)) {
            return violations;
        }
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        CallGraph graph = CallGraph.of(sourceFiles);
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration controller : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                if (!Annotations.hasAny(controller, CONTROLLERS) || Annotations.hasAny(controller, ACCESS_ANNOTATIONS)) {
                    continue;
                }
                for (MethodDeclaration handler : controller.getMethods()) {
                    check(sourceFile, handler, classes, graph, violations);
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

    private void check(
            SourceFile sourceFile, MethodDeclaration handler, ProjectClasses classes, CallGraph graph,
            List<Violation> violations) {
        // Адрес и параметры запроса могут быть объявлены в интерфейсе, который реализует контроллер
        Optional<Handlers.Handler> found = Handlers.of(handler, classes);
        if (found.isEmpty() || found.get().hasAnnotation(ACCESS_ANNOTATIONS)
                || knowsUser(handler) || knowsUser(found.get().declaration())) {
            return;
        }
        for (Parameter parameter : handler.getParameters()) {
            String name = parameter.getNameAsString();
            if (!found.get().hasAnnotation(parameter, REQUEST_VALUES) || !ID_NAME.matcher(name).matches()) {
                continue;
            }
            Optional<Access> access = accessIn(handler, name, classes).or(() -> accessBehind(handler, name, classes, graph));
            if (access.isPresent()) {
                violations.add(violation(sourceFile, parameter,
                        "Обработчик '" + handler.getNameAsString() + "' по идентификатору из запроса ('" + name
                                + "') обращается к записи " + access.get().entity() + " (" + access.get().call()
                                + "), у которой есть владелец ('" + access.get().owner() + "'), но нигде не сверяет"
                                + " его с текущим пользователем: подставив чужой идентификатор, можно прочитать"
                                + " или изменить чужую запись; ищите запись вместе с владельцем"
                                + " (findByIdAndUserId) либо проверяйте права (@PreAuthorize)"));
                return;
            }
        }
    }

    // Обращение к репозиторию прямо в методе
    private Optional<Access> accessIn(MethodDeclaration method, String idName, ProjectClasses classes) {
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            if (!BY_ID_METHODS.contains(call.getNameAsString()) || call.getArguments().size() != 1
                    || !isName(call.getArgument(0), idName) || !Repositories.isRepositoryCall(call)) {
                continue;
            }
            Expression repository = call.getScope().get();
            Optional<ClassOrInterfaceDeclaration> entity = RepositoryEntities.entityOf(repository, classes);
            Optional<String> owner = entity.flatMap(this::ownerField);
            if (owner.isPresent()) {
                return Optional.of(new Access(
                        MethodCalls.receiverName(repository) + "." + call.getNameAsString(),
                        entity.get().getNameAsString(), owner.get()));
            }
        }
        return Optional.empty();
    }

    // Идентификатор передан в сервис, и к репозиторию обращается уже он
    private Optional<Access> accessBehind(MethodDeclaration handler, String idName, ProjectClasses classes, CallGraph graph) {
        for (MethodCallExpr call : handler.findAll(MethodCallExpr.class)) {
            for (int index = 0; index < call.getArguments().size(); index++) {
                if (!isName(call.getArgument(index), idName)) {
                    continue;
                }
                for (MethodDeclaration target : graph.targetsOf(call)) {
                    // Сервис знает о пользователе - проверка владельца, скорее всего, там
                    if (index >= target.getParameters().size() || knowsUser(target)) {
                        continue;
                    }
                    Optional<Access> access = accessIn(target, target.getParameter(index).getNameAsString(), classes);
                    if (access.isPresent()) {
                        return access;
                    }
                }
            }
        }
        return Optional.empty();
    }

    private boolean knowsUser(MethodDeclaration method) {
        boolean byParameter = method.getParameters().stream().anyMatch(parameter ->
                USER_TYPES.contains(LocalTypes.typeName(parameter.getType()))
                        || parameter.getAnnotations().stream()
                        .anyMatch(annotation -> USER_WORD.matcher(annotation.getNameAsString()).matches()));
        return byParameter || method.findAll(SimpleName.class).stream()
                .anyMatch(name -> USER_WORD.matcher(name.getIdentifier()).matches());
    }

    private Optional<String> ownerField(ClassOrInterfaceDeclaration entity) {
        return entity.getFields().stream()
                .flatMap(field -> field.getVariables().stream())
                .map(variable -> variable.getNameAsString())
                .filter(name -> OWNER_FIELDS.contains(name.toLowerCase(Locale.ROOT)))
                .findFirst();
    }

    private boolean isName(Expression expression, String name) {
        Expression value = Nodes.unwrap(expression);
        return value.isNameExpr() && value.asNameExpr().getNameAsString().equals(name);
    }
}
