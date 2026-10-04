package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.SpringBeans;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Component
public class CheckPrototypeInSingletonRule extends AbstractRule implements ProjectRule {
    private static final String SCOPE = "Scope";
    private static final String PROTOTYPE = "prototype";

    // С прокси (proxyMode) Spring сам выдает новый экземпляр на каждое обращение
    private static final String PROXY_MODE = "proxymode";
    private static final Set<String> SHORT_LIVED_SCOPES = Set.of("prototype", "request", "session");

    @Override
    public String code() {
        return RuleCodes.CHECK_PROTOTYPE_IN_SINGLETON_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет prototype-бины, внедренные в singleton напрямую";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        Set<String> prototypes = new HashSet<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                if (scopeOf(type).filter(scope -> scope.contains(PROTOTYPE) && !scope.contains(PROXY_MODE)).isPresent()) {
                    prototypes.add(type.getNameAsString());
                }
            }
        }

        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                if (!SpringBeans.isBean(type) || !isSingleton(type)) {
                    continue;
                }
                // ObjectProvider<Report> и @Lazy откладывают получение - каждый раз приходит новый экземпляр
                SpringBeans.findDependencies(type).stream()
                        .filter(dependency -> !dependency.deferred() && prototypes.contains(dependency.type()))
                        .forEach(dependency -> violations.add(violation(sourceFile, type,
                                "Prototype-бин '" + dependency.type() + "' внедрен в singleton '"
                                        + type.getNameAsString() + "': экземпляр создается один раз вместе"
                                        + " с singleton и дальше общий для всех; получайте его через"
                                        + " ObjectProvider<" + dependency.type() + "> или @Lookup")));
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

    private boolean isSingleton(ClassOrInterfaceDeclaration type) {
        return scopeOf(type).filter(scope -> SHORT_LIVED_SCOPES.stream().anyMatch(scope::contains)).isEmpty();
    }

    // Текст аннотации @Scope в нижнем регистре: в нем видны и "prototype", и SCOPE_PROTOTYPE, и proxyMode
    private Optional<String> scopeOf(ClassOrInterfaceDeclaration type) {
        return Annotations.find(type, SCOPE).map(AnnotationExpr::toString).map(text -> text.toLowerCase(Locale.ROOT));
    }
}
