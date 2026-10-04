package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class CheckMissingEnableAnnotationRule extends AbstractRule implements ProjectRule {
    // Аннотация на методе -> аннотация, без которой Spring ее не замечает
    private static final Map<String, String> REQUIRED = Map.of(
            "Async", "EnableAsync",
            "Scheduled", "EnableScheduling",
            "Cacheable", "EnableCaching",
            "CacheEvict", "EnableCaching",
            "CachePut", "EnableCaching",
            "Retryable", "EnableRetry");

    // Настройка приложения попала в проверку - значит, отсутствие @Enable... не случайность выборки файлов
    private static final Set<String> CONFIGURATION_ANNOTATIONS = Set.of("SpringBootApplication", "Configuration");

    @Override
    public String code() {
        return RuleCodes.CHECK_MISSING_ENABLE_ANNOTATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет @Async, @Scheduled, @Cacheable, @Retryable без включающей их @Enable...";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        Set<String> typeAnnotations = new HashSet<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (Node node : sourceFile.unit().findAll(Node.class)) {
                if (node instanceof TypeDeclaration<?> type) {
                    type.getAnnotations().forEach(annotation -> typeAnnotations.add(nameOf(annotation)));
                }
            }
        }

        List<Violation> violations = new ArrayList<>();
        if (typeAnnotations.stream().noneMatch(CONFIGURATION_ANNOTATIONS::contains)) {
            return violations;
        }

        // Об одной недостающей @Enable... сообщаем один раз, на первом методе
        Set<String> reported = new HashSet<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
                for (AnnotationExpr annotation : method.getAnnotations()) {
                    String enable = REQUIRED.get(nameOf(annotation));
                    if (enable != null && !typeAnnotations.contains(enable) && reported.add(enable)) {
                        violations.add(violation(sourceFile, method,
                                "@" + nameOf(annotation) + " на методе '" + method.getNameAsString() + "' не работает:"
                                        + " в проекте нет @" + enable + ", и Spring эту аннотацию не замечает -"
                                        + " метод выполняется как обычный; добавьте @" + enable + " на класс настроек"));
                    }
                }
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.CRITICAL;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }

    private String nameOf(AnnotationExpr annotation) {
        return annotation.getName().getIdentifier();
    }
}
