package ru.akvine.zond.rules;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.TextFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class CheckDuplicateDependencyRule extends AbstractContextRule {
    @Override
    public String code() {
        return RuleCodes.CHECK_DUPLICATE_DEPENDENCY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует pom.xml и build.gradle и ищет зависимости, объявленные дважды";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<Violation> violations = new ArrayList<>();
        for (TextFile file : context.textFiles()) {
            // Что и где объявлено в этом файле. В Gradle одна библиотека в разных конфигурациях
            // (implementation и annotationProcessor) - обычное дело, поэтому конфигурация входит в ключ
            Map<String, BuildFiles.Dependency> declared = new HashMap<>();
            for (BuildFiles.Dependency dependency : BuildFiles.dependencies(file)) {
                if (dependency.managed()) {
                    continue;
                }
                String key = dependency.coordinates() + (TextFiles.isGradle(file) ? "@" + dependency.scope() : "");
                BuildFiles.Dependency first = declared.putIfAbsent(key, dependency);
                if (first != null) {
                    boolean sameVersion = first.version().equals(dependency.version());
                    violations.add(violation(file.path(), dependency.line(),
                            "Зависимость " + dependency.coordinates() + " уже объявлена на строке " + first.line()
                                    + (sameVersion ? "" : " с другой версией ('" + first.version() + "' и '"
                                    + dependency.version() + "'): какая из них попадет в сборку, зависит от порядка")
                                    + "; оставьте одно объявление"));
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
        return ErrorType.CODE_SMELL;
    }
}
