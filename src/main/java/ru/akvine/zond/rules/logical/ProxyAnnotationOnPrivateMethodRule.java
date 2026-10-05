package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class ProxyAnnotationOnPrivateMethodRule extends AbstractRule {
    // Работают через Spring-прокси. @Transactional здесь нет: его ловит отдельное правило
    private static final Set<String> PROXY_ANNOTATIONS = Set.of(
            "Async", "Cacheable", "CachePut", "CacheEvict", "Retryable", "PreAuthorize", "PostAuthorize", "Secured",
            "RolesAllowed");

    @Override
    public String code() {
        return RuleCodes.PROXY_ANNOTATION_ON_PRIVATE_METHOD_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет @Async, @Cacheable, @Retryable, @PreAuthorize и подобные аннотации"
                + " над приватными методами";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            if (!method.isPrivate()) {
                continue;
            }
            for (AnnotationExpr annotation : method.getAnnotations()) {
                if (PROXY_ANNOTATIONS.contains(annotation.getName().getIdentifier())) {
                    violations.add(violation(sourceFile, annotation,
                            "@" + annotation.getNameAsString() + " над приватным методом '"
                                    + method.getNameAsString() + "' не работает: Spring-прокси не перехватывает"
                                    + " приватные методы, аннотация будет молча проигнорирована"));
                }
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
}
