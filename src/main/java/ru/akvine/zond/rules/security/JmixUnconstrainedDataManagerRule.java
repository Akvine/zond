package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.Jmix;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * UnconstrainedDataManager в коде, которым пользователь управляет напрямую: в экране или REST-контроллере.
 * Он не проверяет ни роли, ни ограничения строк, и все настроенные права доступа перестают действовать.
 */
@Component
public class JmixUnconstrainedDataManagerRule extends AbstractRule {
    private static final Set<String> FACING_ANNOTATIONS = Set.of(
            "ViewController", "UiController", "RestController", "Controller");
    // Экраны Jmix 2 (Flow UI) и Jmix 1 (классический UI)
    private static final Pattern VIEW_TYPE = Pattern.compile(
            "^(Standard(List|Detail|Main)?View|StandardLookup|StandardEditor|MasterDetailScreen|Screen|ScreenFragment|Fragment)$");
    private static final String UNCONSTRAINED = "unconstrained";

    @Override
    public String code() {
        return RuleCodes.JMIX_UNCONSTRAINED_DATA_MANAGER_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует экраны и контроллеры Jmix и ищет работу с данными в обход прав доступа (UnconstrainedDataManager)";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (!isUserFacing(type)) {
                continue;
            }
            List<Node> usages = new ArrayList<>();
            type.findAll(ClassOrInterfaceType.class).stream()
                    .filter(used -> Jmix.UNCONSTRAINED_DATA_MANAGER.equals(used.getNameAsString()))
                    .forEach(usages::add);
            type.findAll(MethodCallExpr.class).stream()
                    .filter(call -> UNCONSTRAINED.equals(call.getNameAsString()) && call.getArguments().isEmpty())
                    .filter(call -> call.getScope().filter(Jmix::isDataManager).isPresent())
                    .forEach(usages::add);
            // Одного сообщения на класс достаточно: причина и исправление одни и те же
            usages.stream().findFirst().ifPresent(usage -> violations.add(violation(sourceFile, usage,
                    "В '" + type.getNameAsString() + "' данные читаются и сохраняются через"
                            + " UnconstrainedDataManager: он не проверяет ни роли, ни ограничения строк, поэтому"
                            + " пользователь увидит и изменит записи, к которым у него нет доступа; используйте"
                            + " обычный DataManager, а обход прав оставьте служебному коду")));
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

    private boolean isUserFacing(ClassOrInterfaceDeclaration type) {
        return Annotations.hasAny(type, FACING_ANNOTATIONS)
                || type.getExtendedTypes().stream().anyMatch(parent -> VIEW_TYPE.matcher(parent.getNameAsString()).matches());
    }
}
