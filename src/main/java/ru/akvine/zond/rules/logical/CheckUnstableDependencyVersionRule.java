package ru.akvine.zond.rules.logical;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.BuildFiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckUnstableDependencyVersionRule extends AbstractContextRule {
    private static final String SNAPSHOT = "-SNAPSHOT";
    private static final Set<String> FLOATING_VERSIONS = Set.of("LATEST", "RELEASE");
    private static final String GRADLE_LATEST = "latest.";
    private static final String PLUS = "+";

    @Override
    public String code() {
        return RuleCodes.CHECK_UNSTABLE_DEPENDENCY_VERSION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует pom.xml и build.gradle и ищет зависимости без точной версии: SNAPSHOT, LATEST, диапазон, плюс";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            for (BuildFiles.Dependency dependency : BuildFiles.dependencies(file, context.textFiles())) {
                describeProblem(dependency.version()).ifPresent(problem -> violations.add(violation(
                        file.path(), dependency.line(),
                        "Зависимость " + dependency.coordinates() + ":" + dependency.version() + " - " + problem
                                + ": сборка одного и того же кода в разные дни даст разный результат;"
                                + " укажите точную версию")));
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
        return ErrorType.LOGICAL;
    }

    private Optional<String> describeProblem(String version) {
        if (version.endsWith(SNAPSHOT)) {
            return Optional.of("версия в разработке, ее содержимое меняется без смены номера");
        }
        if (FLOATING_VERSIONS.contains(version) || version.startsWith(GRADLE_LATEST) || version.endsWith(PLUS)) {
            return Optional.of("версия подставляется самая свежая на момент сборки");
        }
        // [1.0,2.0), (,1.5]
        boolean isRange = (version.startsWith("[") || version.startsWith("(")) && version.contains(",");
        return isRange ? Optional.of("задан диапазон версий, конкретную выберет сборщик") : Optional.empty();
    }
}
