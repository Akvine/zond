package ru.akvine.zond.rules.security;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.AccessMap;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.SecurityMatchers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code requestMatchers("/admin/**").permitAll()}: Spring Security пропускает к административным обработчикам
 * любой запрос. Обычно так делают, когда вызывающего проверяет сам обработчик - общим секретом в теле или
 * параметре запроса. Такая защита слабее входа: секрет один на всех, не истекает, попадает в журналы и
 * подбирается, а забытая проверка в новом обработчике открывает его без единого предупреждения.
 */
@Component
public class AdminPathOpenToAllRule extends AbstractContextRule {
    // /admin/**, /api/admin/**, /v1/backoffice/*: поддерево целиком, а не отдельная страница входа или стили
    private static final Pattern ADMIN_SUBTREE = Pattern.compile(
            "(?i)(/[\\w.-]+)*/(admin|administration|administrator|backoffice|back-office)/\\*{1,2}");
    private static final int SHOWN_CONTROLLERS = 3;

    @Override
    public String code() {
        return RuleCodes.ADMIN_PATH_OPEN_TO_ALL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сверяет контроллеры с настройкой Spring Security и ищет административные адреса (/admin/**),"
                + " открытые правилом permitAll";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        Optional<AccessMap> access = AccessMap.of(context.sources(), context.configFiles());
        if (access.isEmpty()) {
            return List.of();
        }
        ProjectClasses classes = ProjectClasses.of(context.sources());
        SourceFile configuration = context.sources().stream()
                .filter(source -> access.get().matchers().stream()
                        .anyMatch(matcher -> matcher.call().findCompilationUnit()
                                .filter(unit -> unit == source.unit())
                                .isPresent()))
                .findFirst()
                .orElse(null);
        List<Violation> violations = new ArrayList<>();
        for (SecurityMatchers.Matcher open : access.get().matchers()) {
            boolean adminSubtree = !open.any() && open.method() == null
                    && AccessMap.accessOf(open) == AccessMap.Access.OPEN
                    && open.patterns().stream().anyMatch(pattern -> ADMIN_SUBTREE.matcher(pattern).matches());
            if (!adminSubtree || configuration == null) {
                continue;
            }
            // Обработчики, которых правило действительно открывает: без них это статика или адреса библиотеки
            List<AccessMap.Controller> exposed = access.get().controllers().stream()
                    .filter(controller -> controller.paths().stream()
                            .anyMatch(path -> access.get().decide(path).matcher() == open))
                    .filter(controller -> !AccessMap.hasMethodSecurity(controller.type(), classes))
                    .toList();
            if (exposed.isEmpty()) {
                continue;
            }
            List<String> names = exposed.stream().limit(SHOWN_CONTROLLERS).map(AccessMap.Controller::name).toList();
            String shown = String.join(", ", names) + (exposed.size() > SHOWN_CONTROLLERS ? " и другие" : "");
            violations.add(violation(configuration, open.call().getName(),
                    "Правило " + AccessMap.describe(open) + " открывает административные адреса всем: Spring"
                            + " Security пропускает к ним запрос без входа (" + shown + "). Если вызывающего проверяют"
                            + " сами обработчики - общим секретом в запросе, - это слабее входа: секрет один на"
                            + " всех, не истекает, попадает в журналы и подбирается, а обработчик, где проверку"
                            + " забыли, открыт без предупреждения; закройте адреса ролью (hasRole) либо вынесите"
                            + " их на отдельный порт управления, недоступный снаружи"));
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
}
