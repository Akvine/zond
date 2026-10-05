package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.Mappings;
import ru.akvine.zond.rules.support.StringLiterals;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PathVariableMismatchRule extends AbstractRule {
    private static final String PATH_VARIABLE = "PathVariable";
    private static final Set<String> NAME_MEMBERS = Set.of("value", "name");

    // {id}, {id:[0-9]+}, {*rest}
    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{\\*?([^}:/]+)");

    @Override
    public String code() {
        return RuleCodes.PATH_VARIABLE_MISMATCH_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет @PathVariable, имени которого нет в адресе обработчика";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            Optional<AnnotationExpr> mapping = Mappings.find(method);
            Optional<AnnotationExpr> classMapping = method.getParentNode()
                    .filter(parent -> parent instanceof ClassOrInterfaceDeclaration)
                    .flatMap(parent -> Mappings.find((ClassOrInterfaceDeclaration) parent));
            // Адрес задан константой - какие в нем переменные, по коду не узнать
            if (mapping.isEmpty() || Mappings.hasUnknownPath(mapping.get())
                    || classMapping.filter(Mappings::hasUnknownPath).isPresent()) {
                continue;
            }

            Set<String> variables = new HashSet<>();
            collectVariables(mapping.get(), variables);
            classMapping.ifPresent(annotation -> collectVariables(annotation, variables));
            for (Parameter parameter : method.getParameters()) {
                Annotations.find(parameter, PATH_VARIABLE)
                        .map(annotation -> variableName(annotation, parameter))
                        .filter(name -> !variables.contains(name))
                        .ifPresent(name -> violations.add(violation(sourceFile, parameter,
                                "@PathVariable '" + name + "' нет в адресе обработчика '" + method.getNameAsString()
                                        + "': на каждый запрос Spring ответит ошибкой 500; имя должно совпадать"
                                        + " с переменной в фигурных скобках")));
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

    private void collectVariables(AnnotationExpr mapping, Set<String> variables) {
        for (String path : Mappings.paths(mapping)) {
            Matcher matcher = TEMPLATE_VARIABLE.matcher(path);
            while (matcher.find()) {
                variables.add(matcher.group(1));
            }
        }
    }

    // @PathVariable("id") Long userId -> id; @PathVariable Long id -> id
    private String variableName(AnnotationExpr annotation, Parameter parameter) {
        Optional<Expression> explicit = Optional.empty();
        if (annotation.isSingleMemberAnnotationExpr()) {
            explicit = Optional.of(annotation.asSingleMemberAnnotationExpr().getMemberValue());
        } else if (annotation.isNormalAnnotationExpr()) {
            explicit = annotation.asNormalAnnotationExpr().getPairs().stream()
                    .filter(pair -> NAME_MEMBERS.contains(pair.getNameAsString()))
                    .map(pair -> pair.getValue())
                    .findFirst();
        }
        return explicit.flatMap(StringLiterals::textOf).orElse(parameter.getNameAsString());
    }
}
