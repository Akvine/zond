package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
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
import ru.akvine.zond.rules.support.HotMethods;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code static Type from(String code) { for (Type type : values()) if (type.code.equals(code)) return type; ... }}:
 * {@code values()} при каждом вызове копирует массив констант, а затем константы перебираются по одной. Пока метод
 * вызывают изредка, это мелочь; когда его вызывают на каждом элементе данных, копий массива и переборов столько,
 * сколько элементов. Статическая карта "значение → константа" строится один раз.
 */
@Component
public class EnumLookupByValuesScanRule extends AbstractRule implements ProjectRule {
    private static final String VALUES = "values";
    // У перечисления из двух-трех констант перебор не дороже поиска в карте
    private static final int MIN_CONSTANTS = 4;

    @Override
    public String code() {
        return RuleCodes.ENUM_LOOKUP_BY_VALUES_SCAN_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет поиск константы перечисления перебором values() в методе, который вызывается"
                + " на каждом элементе цикла или стрима";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        HotMethods hotMethods = null;
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            if (TestPaths.isTest(sourceFile.path())) {
                continue;
            }
            for (EnumDeclaration type : sourceFile.unit().findAll(EnumDeclaration.class)) {
                if (type.getEntries().size() < MIN_CONSTANTS) {
                    continue;
                }
                for (MethodDeclaration method : type.getMethods()) {
                    Optional<MethodCallExpr> scan = findScan(type, method);
                    if (scan.isEmpty()) {
                        continue;
                    }
                    if (hotMethods == null) {
                        hotMethods = HotMethods.of(sourceFiles);
                    }
                    Optional<HotMethods.HotSite> site = hotMethods.find(method);
                    if (site.isEmpty()) {
                        continue;
                    }
                    Violation violation = violation(sourceFile, scan.get(),
                            "'" + type.getNameAsString() + "." + method.getNameAsString() + "' ищет константу"
                                    + " перебором values(): массив констант копируется при каждом вызове, а метод"
                                    + " вызывается на каждом элементе (" + site.get().place() + "); заведите"
                                    + " статическую Map \"значение → константа\" и ищите в ней");
                    violations.add(site.get().exact() ? violation : violation.withConfidence(Confidence.SUSPICION));
                }
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

    /**
     * @return вызов values() в статическом методе поиска: один параметр, и результат зависит от него
     */
    private Optional<MethodCallExpr> findScan(EnumDeclaration type, MethodDeclaration method) {
        if (!method.isStatic() || method.getParameters().size() != 1 || method.getBody().isEmpty()) {
            return Optional.empty();
        }
        String parameter = method.getParameter(0).getNameAsString();
        boolean usesParameter = method.findAll(NameExpr.class).stream()
                .anyMatch(name -> name.getNameAsString().equals(parameter));
        if (!usesParameter) {
            return Optional.empty();
        }
        return method.findAll(MethodCallExpr.class).stream()
                .filter(call -> VALUES.equals(call.getNameAsString()) && call.getArguments().isEmpty()
                        && call.getScope()
                        .map(scope -> scope.toString().equals(type.getNameAsString()))
                        .orElse(true))
                .findFirst();
    }
}
