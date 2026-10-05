package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.PackageDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class PackageCycleRule extends AbstractRule implements ProjectRule {
    private static final String SEPARATOR = ".";

    /**
     * Импорт, из-за которого один пакет зависит от другого
     */
    private record Dependency(SourceFile sourceFile, ImportDeclaration importDeclaration) {
    }

    @Override
    public String code() {
        return RuleCodes.PACKAGE_CYCLE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет пакеты, которые зависят друг от друга взаимно";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        Set<String> packages = new HashSet<>();
        for (SourceFile sourceFile : sourceFiles) {
            packages.add(packageOf(sourceFile));
        }

        // Пакет -> пакет, от которого он зависит -> первый импорт, который эту зависимость создает
        Map<String, Map<String, Dependency>> dependencies = new HashMap<>();
        for (SourceFile sourceFile : sourceFiles) {
            String from = packageOf(sourceFile);
            for (ImportDeclaration importDeclaration : sourceFile.unit().getImports()) {
                String to = importedPackage(importDeclaration);
                if (packages.contains(to) && !isNested(from, to)) {
                    dependencies.computeIfAbsent(from, key -> new HashMap<>())
                            .putIfAbsent(to, new Dependency(sourceFile, importDeclaration));
                }
            }
        }

        List<Violation> violations = new ArrayList<>();
        dependencies.forEach((from, targets) -> targets.forEach((to, dependency) -> {
            // О паре сообщаем один раз - со стороны пакета, который идет первым по алфавиту
            boolean mutual = dependencies.getOrDefault(to, Map.of()).containsKey(from);
            if (mutual && from.compareTo(to) < 0) {
                violations.add(violation(dependency.sourceFile(), dependency.importDeclaration(),
                        "Пакеты '" + from + "' и '" + to + "' зависят друг от друга: их нельзя ни понять, ни изменить,"
                                + " ни вынести по отдельности; уберите зависимость в одну из сторон"));
            }
        }));
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

    private String packageOf(SourceFile sourceFile) {
        return sourceFile.unit().getPackageDeclaration().map(PackageDeclaration::getNameAsString).orElse("");
    }

    // import a.b.Foo -> a.b; import static a.b.Foo.bar и import a.b.* тоже приводятся к пакету
    private String importedPackage(ImportDeclaration importDeclaration) {
        String name = importDeclaration.getNameAsString();
        if (importDeclaration.isAsterisk() && !importDeclaration.isStatic()) {
            return name;
        }
        if (importDeclaration.isStatic() && !importDeclaration.isAsterisk()) {
            name = parent(name);
        }
        return parent(name);
    }

    private String parent(String name) {
        int separator = name.lastIndexOf(SEPARATOR);
        return separator < 0 ? "" : name.substring(0, separator);
    }

    // Пакет и его подпакет - части одного целого: их взаимная зависимость обычна
    private boolean isNested(String first, String second) {
        return first.equals(second)
                || first.startsWith(second + SEPARATOR)
                || second.startsWith(first + SEPARATOR);
    }
}
