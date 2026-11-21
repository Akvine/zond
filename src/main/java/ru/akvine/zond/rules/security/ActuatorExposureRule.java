package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.body.MethodDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ConfigFile;
import ru.akvine.zond.models.ConfigProperty;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractConfigRule;
import ru.akvine.zond.rules.ContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.SecurityMatchers;
import ru.akvine.zond.rules.support.SecurityMatchers.Matcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Служебные адреса Actuator, доступные снаружи: в настройках открыты все разом либо правило доступа пускает к
 * ним без аутентификации, а открыто больше, чем проверка состояния
 */
@Component
public class ActuatorExposureRule extends AbstractConfigRule implements ContextRule {
    private static final String EXPOSURE = "management.endpoints.web.exposure.include";
    private static final List<String> MANAGEMENT_ENDPOINT_KEYS =
            List.of("management.server.port", "management.server.address");
    private static final String BASE_PATH = "management.endpoints.web.base-path";
    private static final String DEFAULT_BASE_PATH = "/actuator";
    private static final String ALL = "*";
    // /actuator/**, /actuator/*: правило на все служебные адреса разом
    private static final Set<String> WHOLE_TREE = Set.of("/**", "/*");
    // Проверку состояния и сведения о сборке открывают намеренно: по ним живет балансировщик
    private static final Set<String> HARMLESS = Set.of("health", "info");
    // Метрики секретов не содержат, но показывают устройство и нагрузку приложения
    private static final Set<String> METRICS = Set.of("prometheus", "metrics");

    @Override
    public String code() {
        return RuleCodes.ACTUATOR_EXPOSURE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует настройки и правила доступа и ищет Actuator, открытый наружу целиком или без аутентификации";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return List.of();
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        context.configFiles().forEach(configFile -> violations.addAll(checkConfig(configFile)));
        Set<String> exposed = exposedEndpoints(context.configFiles());
        exposed.removeAll(HARMLESS);
        if (exposed.isEmpty()) {
            return violations;
        }
        String basePath = context.configFiles().stream()
                .filter(configFile -> !configFile.isNonProduction())
                .flatMap(configFile -> configFile.find(BASE_PATH).stream())
                .map(ConfigProperty::value)
                .findFirst()
                .orElse(DEFAULT_BASE_PATH);
        boolean separated = context.configFiles().stream().anyMatch(this::isSeparated);
        for (SourceFile sourceFile : context.sources()) {
            for (MethodDeclaration method : sourceFile.findAll(MethodDeclaration.class)) {
                if (!SecurityMatchers.configures(method)) {
                    continue;
                }
                SecurityMatchers.of(method).stream()
                        .filter(matcher -> matcher.permitsAll() && opensWholeTree(matcher, basePath))
                        .map(matcher -> open(sourceFile, matcher, exposed, separated))
                        .forEach(violations::add);
            }
        }
        return violations;
    }

    @Override
    public List<Violation> checkConfig(ConfigFile configFile) {
        if (configFile.isNonProduction()) {
            return List.of();
        }
        Optional<ConfigProperty> exposure = configFile.find(EXPOSURE)
                .filter(property -> property.value().contains(ALL) && !configFile.isNonProduction(property));
        if (exposure.isEmpty()) {
            return List.of();
        }
        Violation violation = violation(configFile, exposure.get().line(),
                EXPOSURE + "=*: по HTTP доступны все служебные адреса Actuator, включая /env (переменные"
                        + " окружения с паролями), /heapdump (дамп памяти) и /shutdown; перечислите только"
                        + " нужные: health, info, prometheus");
        // management.server.port / management.server.address: служебные адреса вынесены на отдельный порт или
        // слушают только свой узел. Снаружи их обычно не видно, но это зависит от сети, поэтому подозрение
        return List.of(isSeparated(configFile) ? violation.withConfidence(Confidence.SUSPICION) : violation);
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    private boolean isSeparated(ConfigFile configFile) {
        return MANAGEMENT_ENDPOINT_KEYS.stream().anyMatch(key -> configFile.find(key).isPresent());
    }

    // Все, что рабочие настройки выставляют по HTTP: prometheus, env, * ...
    private Set<String> exposedEndpoints(List<ConfigFile> configFiles) {
        Set<String> exposed = new LinkedHashSet<>();
        configFiles.stream()
                .filter(configFile -> !configFile.isNonProduction())
                .forEach(configFile -> configFile.find(EXPOSURE)
                        .filter(property -> !configFile.isNonProduction(property))
                        .ifPresent(property -> Arrays.stream(property.value().split(","))
                                .map(String::trim)
                                .filter(endpoint -> !endpoint.isEmpty())
                                .forEach(exposed::add)));
        return exposed;
    }

    private boolean opensWholeTree(Matcher matcher, String basePath) {
        return matcher.patterns() != null && matcher.patterns().stream()
                .anyMatch(pattern -> WHOLE_TREE.stream().anyMatch(tree -> pattern.equals(basePath + tree)));
    }

    private Violation open(SourceFile sourceFile, Matcher matcher, Set<String> exposed, boolean separated) {
        boolean metricsOnly = METRICS.containsAll(exposed);
        Violation violation = violation(sourceFile, matcher.call().getName(),
                "Служебные адреса Actuator открыты без аутентификации (" + matcher.call().getNameAsString()
                        + "(...).permitAll()), а " + EXPOSURE + " выставляет по HTTP " + String.join(", ", exposed)
                        + ": " + (metricsOnly
                        ? "метрики приложения увидит любой, кто дотянется до порта"
                        : "их прочтет любой, кто дотянется до порта, - вместе с настройками и состоянием приложения")
                        + "; без входа оставьте только health, остальное закройте ролью либо вынесите на"
                        + " отдельный порт (management.server.port)")
                .withLevel(metricsOnly ? ErrorLevel.MINOR : ErrorLevel.MAJOR);
        // Отдельный порт для служебных адресов обслуживает своя цепочка: правило основной к ним может не относиться
        return separated ? violation.withConfidence(Confidence.SUSPICION) : violation;
    }
}
