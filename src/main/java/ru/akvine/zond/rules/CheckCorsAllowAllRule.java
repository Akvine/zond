package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Component
public class CheckCorsAllowAllRule extends AbstractRule {
    private static final String CROSS_ORIGIN = "CrossOrigin";
    private static final String ANY_ORIGIN = "\"*\"";
    private static final String ORIGINS = "origins";
    private static final String ORIGIN_PATTERNS = "originPatterns";
    private static final Set<String> ORIGIN_METHODS = Set.of(
            "allowedOrigins", "allowedOriginPatterns", "addAllowedOrigin", "addAllowedOriginPattern",
            "setAllowedOrigins", "setAllowedOriginPatterns");

    @Override
    public String code() {
        return RuleCodes.CHECK_CORS_ALLOW_ALL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет CORS, открытый для любых сайтов";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (AnnotationExpr annotation : sourceFile.unit().findAll(AnnotationExpr.class)) {
            if (CROSS_ORIGIN.equals(annotation.getName().getIdentifier()) && allowsAnyOrigin(annotation)) {
                violations.add(report(sourceFile, annotation));
            }
        }
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (ORIGIN_METHODS.contains(call.getNameAsString()) && call.toString().contains(ANY_ORIGIN)) {
                violations.add(report(sourceFile, call));
            }
        }
        violations.sort(Comparator.comparingInt(Violation::line));
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

    // @CrossOrigin без списка сайтов разрешает все; так же и origins = "*"
    private boolean allowsAnyOrigin(AnnotationExpr annotation) {
        String text = annotation.toString();
        boolean listsOrigins = annotation.isSingleMemberAnnotationExpr()
                || text.contains(ORIGINS) || text.contains(ORIGIN_PATTERNS);
        return !listsOrigins || text.contains(ANY_ORIGIN);
    }

    private Violation report(SourceFile sourceFile, Node node) {
        return violation(sourceFile, node,
                "CORS разрешен для любых сайтов: чужая страница сможет обращаться к API от имени пользователя,"
                        + " который на нее зашел; перечислите доверенные адреса явно");
    }
}
