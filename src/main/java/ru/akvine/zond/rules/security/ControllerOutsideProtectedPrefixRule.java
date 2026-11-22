package ru.akvine.zond.rules.security;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.AccessMap;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.SecurityMatchers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Доступ к административным контроллерам закрыт по приставке адреса: {@code /shop/admin/**} - только для роли,
 * остальное {@code /shop/**} открыто. Контроллер, который лежит в том же пакете {@code admin}, но получил адрес
 * без приставки ({@code /shop/cashier}), под закрытое правило не попадает и молча оказывается открыт: его
 * обработчики вызывает любой, и ни ошибки, ни предупреждения при этом нет.
 */
@Component
public class ControllerOutsideProtectedPrefixRule extends AbstractContextRule {
    private static final String SUBTREE = "/**";
    private static final Pattern PLAIN_PREFIX = Pattern.compile("(/[\\w.-]+)+");
    // Вход, выход и служебные адреса открыты намеренно, даже если лежат рядом с закрытыми контроллерами
    private static final Pattern OPEN_BY_PURPOSE = Pattern.compile(
            "(?i).*(auth|login|logout|sign[-_]?(in|up|out)|regist|token|public|callback|web[-_]?hook|health|ping"
                    + "|captcha|password|restore|recover).*");
    // Закрытых соседей должно быть столько, чтобы правило читалось как принятый в проекте порядок
    private static final int MIN_PROTECTED_NEIGHBOURS = 2;

    @Override
    public String code() {
        return RuleCodes.CONTROLLER_OUTSIDE_PROTECTED_PREFIX_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сверяет контроллеры с настройкой Spring Security и ищет контроллер, который лежит в одном пакете с"
                + " закрытыми, но из-за адреса без общей приставки открыт всем";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        Optional<AccessMap> access = AccessMap.of(context.sources(), context.configFiles());
        if (access.isEmpty()) {
            return List.of();
        }
        ProjectClasses classes = ProjectClasses.of(context.sources());
        List<Violation> violations = new ArrayList<>();
        for (SecurityMatchers.Matcher closed : access.get().matchers()) {
            if (closed.any() || closed.method() != null || AccessMap.accessOf(closed) != AccessMap.Access.CLOSED) {
                continue;
            }
            for (String pattern : closed.patterns()) {
                protectedWord(pattern)
                        .ifPresent(word -> check(access.get(), closed, pattern, word, classes, violations));
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

    // /shop/admin/** -> admin: последняя часть приставки, по которой закрыт доступ
    private Optional<String> protectedWord(String pattern) {
        if (!pattern.endsWith(SUBTREE)) {
            return Optional.empty();
        }
        String prefix = pattern.substring(0, pattern.length() - SUBTREE.length());
        return PLAIN_PREFIX.matcher(prefix).matches()
                ? Optional.of(prefix.substring(prefix.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT))
                : Optional.empty();
    }

    private void check(AccessMap access, SecurityMatchers.Matcher closed, String pattern, String word,
                       ProjectClasses classes, List<Violation> violations) {
        // Соседи - контроллеры одного пакета, названного так же, как закрытая приставка: controller.admin и
        // /admin/**. Пакеты с тем же последним словом в других модулях - отдельные семейства
        List<String> packages = access.controllers().stream()
                .map(AccessMap.Controller::packageName)
                .filter(name -> lastSegment(name).equals(word))
                .distinct()
                .toList();
        for (String packageName : packages) {
            List<AccessMap.Controller> neighbours = access.controllers().stream()
                    .filter(controller -> controller.packageName().equals(packageName))
                    .toList();
            check(access, closed, pattern, neighbours, classes, violations);
        }
    }

    private void check(AccessMap access, SecurityMatchers.Matcher closed, String pattern,
                       List<AccessMap.Controller> neighbours, ProjectClasses classes, List<Violation> violations) {
        List<AccessMap.Controller> behind = neighbours.stream()
                .filter(controller -> access.decide(controller)
                        .filter(decision -> decision.matcher() == closed)
                        .isPresent())
                .toList();
        if (behind.size() < MIN_PROTECTED_NEIGHBOURS) {
            return;
        }
        // Открытые соседи, которым по смыслу положено быть закрытыми: вход и служебные адреса открыты намеренно
        List<AccessMap.Controller> suspects = neighbours.stream()
                .filter(controller -> access.decide(controller)
                        .filter(found -> found.access() == AccessMap.Access.OPEN && found.matcher() != null)
                        .isPresent())
                .filter(controller -> !OPEN_BY_PURPOSE.matcher(controller.name()).matches()
                        && !controller.paths().stream().allMatch(path -> OPEN_BY_PURPOSE.matcher(path).matches())
                        && !AccessMap.hasMethodSecurity(controller.type(), classes))
                .toList();
        // Их должно быть меньшинство: иначе пакет - не признак закрытого контроллера
        if (suspects.size() * 2 > behind.size()) {
            return;
        }
        String prefix = pattern.substring(0, pattern.length() - SUBTREE.length());
        for (AccessMap.Controller controller : suspects) {
            SecurityMatchers.Matcher open = access.decide(controller).orElseThrow().matcher();
            violations.add(violation(controller.source(), controller.type().getName(),
                    "Контроллер '" + controller.name() + "' лежит в пакете '" + controller.packageName() + "' вместе с "
                            + behind.size() + " контроллерами, которые закрыты правилом " + AccessMap.describe(closed)
                            + ", но его адрес '" + controller.paths().get(0) + "' под это правило не попадает и открыт"
                            + " правилом " + AccessMap.describe(open) + ": его обработчики доступны без входа. Если"
                            + " контроллер административный, перенесите его адрес под '" + prefix + "' либо добавьте"
                            + " для него свое правило доступа"));
        }
    }

    private String lastSegment(String packageName) {
        return packageName.substring(packageName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
