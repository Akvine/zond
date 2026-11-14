package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.SimpleName;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.TestPaths;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.HotMethods;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code getDeclaredFields()}, {@code getDeclaredMethods()} и {@code Introspector.getBeanInfo(...)} при каждом вызове
 * заново копируют описание класса. В методе, который вызывают изредка, это мелочь; когда его вызывают на каждом
 * элементе данных - а объекты там обычно одного класса, - один и тот же список полей собирается столько раз,
 * сколько элементов. Рефлексию прямо в цикле показывает jr:419, здесь - метод, который вызывают из цикла.
 */
@Component
public class HiddenReflectionInHotMethodRule extends AbstractRule implements ProjectRule {
    private static final Set<String> CLASS_SCANS = Set.of(
            "getDeclaredFields", "getDeclaredMethods", "getMethods", "getFields", "getDeclaredConstructors",
            "getRecordComponents");
    private static final String GET_BEAN_INFO = "getBeanInfo";
    // Метод сам хранит результат: поле-кеш либо computeIfAbsent
    private static final Pattern CACHE_TYPE = Pattern.compile(".*(Map|Cache|ClassValue)$");
    private static final String COMPUTE_IF_ABSENT = "computeIfAbsent";

    @Override
    public String code() {
        return RuleCodes.HIDDEN_REFLECTION_IN_HOT_METHOD_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет getDeclaredFields, getDeclaredMethods и Introspector.getBeanInfo в методах,"
                + " которые вызываются на каждом элементе цикла или стрима, без кеша по классу";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        HotMethods hotMethods = null;
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            if (TestPaths.isTest(sourceFile.path())) {
                continue;
            }
            // Один метод - одна находка, сколько бы раз он ни обращался к рефлексии
            Set<MethodDeclaration> reported = Collections.newSetFromMap(new IdentityHashMap<>());
            for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
                // Рефлексия прямо в цикле по данным - находка jr:419
                if (!isClassScan(call) || CodeContexts.isRepeatedOverData(call) || CodeContexts.isStartup(call)
                        || TestClasses.isInside(call)) {
                    continue;
                }
                Optional<MethodDeclaration> method = call.findAncestor(MethodDeclaration.class);
                if (method.isEmpty() || reported.contains(method.get()) || isCached(method.get())) {
                    continue;
                }
                if (hotMethods == null) {
                    hotMethods = HotMethods.of(sourceFiles);
                }
                Optional<HotMethods.HotSite> site = hotMethods.find(method.get());
                if (site.isEmpty()) {
                    continue;
                }
                reported.add(method.get());
                Violation violation = violation(sourceFile, call.getName(),
                        "'" + call.getNameAsString() + "(...)' в методе '" + method.get().getNameAsString()
                                + "', который вызывается на каждом элементе (" + site.get().place() + "): рефлексия"
                                + " заново собирает описание класса на каждый элемент, хотя у объектов одного"
                                + " класса оно одинаково; сохраняйте результат по классу - ClassValue либо"
                                + " ConcurrentHashMap.computeIfAbsent(type, ...)");
                violations.add(site.get().exact() ? violation : violation.withConfidence(Confidence.SUSPICION));
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

    private boolean isClassScan(MethodCallExpr call) {
        String name = call.getNameAsString();
        if (GET_BEAN_INFO.equals(name)) {
            return call.getScope().filter(scope -> scope.toString().endsWith("Introspector")).isPresent();
        }
        return CLASS_SCANS.contains(name) && call.getArguments().isEmpty() && call.getScope().isPresent();
    }

    // Метод обращается к полю-кешу своего класса либо сам вызывается как загрузчик computeIfAbsent
    private boolean isCached(MethodDeclaration method) {
        boolean computes = method.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> COMPUTE_IF_ABSENT.equals(call.getNameAsString()));
        if (computes) {
            return true;
        }
        for (SimpleName name : method.findAll(SimpleName.class)) {
            Optional<VariableDeclarator> field = LocalTypes.findField(name, name.getIdentifier());
            if (field.isPresent() && CACHE_TYPE.matcher(LocalTypes.typeName(field.get().getType())).matches()) {
                return true;
            }
        }
        return false;
    }
}
