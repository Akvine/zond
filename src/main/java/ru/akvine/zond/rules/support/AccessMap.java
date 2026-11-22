package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.SourceFile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Настройка доступа Spring Security, сверенная с контроллерами проекта: какое правило решает судьбу запроса
 * на адрес контроллера. Строится, только когда картина видна целиком: настройка в проекте одна, все ее
 * шаблоны заданы строками, общей приставки к адресам нет
 */
public final class AccessMap {
    private static final Set<String> CONTROLLERS = Set.of("RestController", "Controller");
    // Проверка доступа на самом классе или методе: правило для адреса тогда не единственная защита
    private static final Set<String> METHOD_SECURITY = Set.of(
            "PreAuthorize", "PostAuthorize", "Secured", "RolesAllowed", "DenyAll", "PreFilter");
    private static final Set<String> OPEN_ACCESS = Set.of("permitAll", "anonymous");
    private static final Set<String> CLOSED_ACCESS = Set.of(
            "authenticated", "fullyAuthenticated", "hasRole", "hasAnyRole", "hasAuthority", "hasAnyAuthority",
            "denyAll");
    // Цепочка относится только к части адресов либо адреса получают общую приставку
    private static final Set<String> SCOPING = Set.of(
            "securityMatcher", "securityMatchers", "requestMatcher", "antMatcher", "mvcMatcher", "regexMatcher",
            "addPathPrefix");
    private static final Set<String> PATH_PREFIX_KEYS = Set.of("spring.mvc.servlet.path", "spring.webflux.base-path");
    private static final String ROOT = "/";
    private static final Pattern SLASHES = Pattern.compile("/{2,}");
    private static final String SLASH = "/";

    public enum Access {
        OPEN, CLOSED, UNKNOWN
    }

    /**
     * @param matcher правило, которое сработало; null, если адрес выведен из-под Spring Security или правила нет
     */
    public record Decision(Access access, SecurityMatchers.Matcher matcher) {
    }

    /**
     * Контроллер и адреса, на которых он принимает запросы
     *
     * @param packageName пакет класса; пустая строка, если он не назван
     */
    public record Controller(ClassOrInterfaceDeclaration type, SourceFile source, String packageName,
                             List<String> paths) {

        public String name() {
            return type.getNameAsString();
        }
    }

    private final List<SecurityMatchers.Matcher> matchers;
    private final List<String> ignored;
    private final List<Controller> controllers;

    private AccessMap(List<SecurityMatchers.Matcher> matchers, List<String> ignored, List<Controller> controllers) {
        this.matchers = List.copyOf(matchers);
        this.ignored = List.copyOf(ignored);
        this.controllers = List.copyOf(controllers);
    }

    /**
     * @return пусто, если настройка доступа в проекте не одна, часть ее шаблонов задана не строками либо адреса
     * обработчиков получают приставку, которой в аннотациях нет
     */
    public static Optional<AccessMap> of(List<SourceFile> sources, List<ConfigFile> configFiles) {
        List<MethodDeclaration> configurations = sources.stream()
                .flatMap(source -> source.findAll(MethodDeclaration.class).stream())
                .filter(method -> SecurityMatchers.configures(method) && !TestClasses.isInside(method))
                .toList();
        boolean prefixed = configFiles.stream()
                .filter(configFile -> !configFile.isNonProduction())
                .anyMatch(configFile -> PATH_PREFIX_KEYS.stream()
                        .flatMap(key -> configFile.find(key).stream())
                        .anyMatch(property -> !ROOT.equals(property.value().trim()) && !property.value().isBlank()));
        boolean scoped = sources.stream()
                .flatMap(source -> source.findAll(MethodCallExpr.class).stream())
                .anyMatch(call -> SCOPING.contains(call.getNameAsString()) && !TestClasses.isInside(call));
        if (configurations.size() != 1 || prefixed || scoped) {
            return Optional.empty();
        }
        List<SecurityMatchers.Matcher> matchers = SecurityMatchers.of(configurations.get(0));
        if (matchers.stream().anyMatch(matcher -> !matcher.any() && matcher.patterns() == null)) {
            return Optional.empty();
        }
        List<String> ignored = new ArrayList<>();
        for (SourceFile source : sources) {
            Optional<List<String>> patterns = SecurityMatchers.ignored(source.unit());
            if (patterns.isEmpty()) {
                return Optional.empty();
            }
            ignored.addAll(patterns.get());
        }
        ProjectClasses classes = ProjectClasses.of(sources);
        List<Controller> controllers = new ArrayList<>();
        for (SourceFile source : sources) {
            for (ClassOrInterfaceDeclaration type : source.findAll(ClassOrInterfaceDeclaration.class)) {
                if (type.isInterface() || type.isAbstract() || !Annotations.hasAny(type, CONTROLLERS)
                        || TestClasses.isInside(type)) {
                    continue;
                }
                List<String> paths = pathsOf(type, classes);
                if (!paths.isEmpty()) {
                    String packageName = type.findCompilationUnit()
                            .flatMap(CompilationUnit::getPackageDeclaration)
                            .map(declaration -> declaration.getNameAsString())
                            .orElse("");
                    controllers.add(new Controller(type, source, packageName, paths));
                }
            }
        }
        return Optional.of(new AccessMap(matchers, ignored, controllers));
    }

    public List<SecurityMatchers.Matcher> matchers() {
        return matchers;
    }

    public List<Controller> controllers() {
        return controllers;
    }

    /**
     * @return правило, которое решает судьбу запроса на этот адрес: первое подходящее, как и в самом Spring
     * Security. Правило только для одного HTTP-метода ответа не дает: метод запроса здесь неизвестен
     */
    public Decision decide(String path) {
        if (ignored.stream().anyMatch(pattern -> SecurityMatchers.matches(pattern, path))) {
            return new Decision(Access.OPEN, null);
        }
        for (SecurityMatchers.Matcher matcher : matchers) {
            boolean applies = matcher.any() || matcher.patterns().isEmpty() || matcher.patterns().stream()
                    .anyMatch(pattern -> SecurityMatchers.matches(pattern, path));
            if (!applies) {
                continue;
            }
            return matcher.method() == null
                    ? new Decision(accessOf(matcher), matcher)
                    : new Decision(Access.UNKNOWN, matcher);
        }
        return new Decision(Access.UNKNOWN, null);
    }

    /**
     * @return правило, которое решает судьбу всех адресов контроллера сразу; пусто, если адреса расходятся по
     * разным правилам
     */
    public Optional<Decision> decide(Controller controller) {
        List<Decision> decisions = controller.paths().stream().map(this::decide).toList();
        if (decisions.isEmpty()) {
            return Optional.empty();
        }
        Decision first = decisions.get(0);
        boolean same = decisions.stream()
                .allMatch(decision -> decision.access() == first.access() && decision.matcher() == first.matcher());
        return same ? Optional.of(first) : Optional.empty();
    }

    public static Access accessOf(SecurityMatchers.Matcher matcher) {
        String name = accessName(matcher);
        if (OPEN_ACCESS.contains(name)) {
            return Access.OPEN;
        }
        // access(...) и свои условия: кого они пускают, по коду не узнать
        return CLOSED_ACCESS.contains(name) ? Access.CLOSED : Access.UNKNOWN;
    }

    /**
     * @return правило так, как оно записано в коде: 'requestMatchers("/admin/**").hasRole("ADMIN")'
     */
    public static String describe(SecurityMatchers.Matcher matcher) {
        String target = matcher.any()
                ? matcher.call().getNameAsString() + "()"
                : matcher.call().getNameAsString() + "(\"" + String.join("\", \"", matcher.patterns()) + "\")";
        int arguments = matcher.access().indexOf('[');
        String values = arguments < 0 ? "" : matcher.access().substring(arguments + 1, matcher.access().length() - 1);
        return "'" + target + "." + accessName(matcher) + "(" + values + ")'";
    }

    /**
     * @return true, если доступ проверяют и аннотации на классе, его предке либо обработчиках
     */
    public static boolean hasMethodSecurity(ClassOrInterfaceDeclaration type, ProjectClasses classes) {
        Optional<ClassOrInterfaceDeclaration> current = Optional.of(type);
        Set<ClassOrInterfaceDeclaration> seen = new LinkedHashSet<>();
        while (current.isPresent() && seen.add(current.get())) {
            ClassOrInterfaceDeclaration checked = current.get();
            if (Annotations.hasAny(checked, METHOD_SECURITY)
                    || checked.getMethods().stream().anyMatch(method -> Annotations.hasAny(method, METHOD_SECURITY))) {
                return true;
            }
            current = classes.parent(checked);
        }
        return false;
    }

    private static String accessName(SecurityMatchers.Matcher matcher) {
        int arguments = matcher.access().indexOf('[');
        return arguments < 0 ? matcher.access() : matcher.access().substring(0, arguments);
    }

    /**
     * @return адреса обработчиков класса вместе с его приставкой, включая обработчики, унаследованные от предка;
     * пусто, если обработчиков нет либо хоть один адрес задан не строкой
     */
    private static List<String> pathsOf(ClassOrInterfaceDeclaration type, ProjectClasses classes) {
        // Приставку Spring берет с класса, а если на нем ее нет - с интерфейса, который он реализует
        Optional<AnnotationExpr> typeMapping = Mappings.find(type);
        for (ClassOrInterfaceType implemented : type.getImplementedTypes()) {
            if (typeMapping.isEmpty()) {
                typeMapping = classes.resolve(implemented).filter(ClassOrInterfaceDeclaration::isInterface)
                        .flatMap(Mappings::find);
            }
        }
        if (typeMapping.filter(Mappings::hasUnknownPath).isPresent()) {
            return List.of();
        }
        List<String> prefixes = typeMapping.map(Mappings::paths).orElse(List.of(""));

        List<AnnotationExpr> handlers = new ArrayList<>();
        for (MethodDeclaration method : type.getMethods()) {
            Handlers.of(method, classes).ifPresent(handler -> handlers.add(handler.mapping()));
        }
        // Обработчики общего предка: BaseController с /list, /get, /create
        Optional<ClassOrInterfaceDeclaration> parent = classes.parent(type);
        Set<ClassOrInterfaceDeclaration> seen = new LinkedHashSet<>();
        while (parent.isPresent() && seen.add(parent.get())) {
            parent.get().getMethods().forEach(method -> Mappings.find(method).ifPresent(handlers::add));
            parent = classes.parent(parent.get());
        }
        if (handlers.stream().anyMatch(Mappings::hasUnknownPath)) {
            return List.of();
        }
        // Обработчики остались в предке, которого в проекте не видно: судим по приставке самого класса
        List<List<String>> handlerPaths = handlers.isEmpty() && typeMapping.isPresent()
                ? List.of(List.of(""))
                : handlers.stream().map(Mappings::paths).toList();
        Set<String> paths = new LinkedHashSet<>();
        for (String prefix : prefixes) {
            for (List<String> handler : handlerPaths) {
                for (String path : handler) {
                    String joined = SLASHES.matcher(SLASH + prefix + SLASH + path).replaceAll(SLASH);
                    paths.add(joined.length() > 1 && joined.endsWith(SLASH)
                            ? joined.substring(0, joined.length() - 1)
                            : joined);
                }
            }
        }
        // ${api.prefix}/orders: приставка приходит из настроек
        return paths.stream().anyMatch(path -> path.contains("$")) ? List.of() : List.copyOf(paths);
    }
}
