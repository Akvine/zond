package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
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
import ru.akvine.zond.rules.support.JpaProperties;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.Queries;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class UnknownFieldInQueryRule extends AbstractRule implements ProjectRule {
    private static final String ENTITY = "Entity";
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");

    // from Order o, update Order o, delete from Order o, а также второй корень через запятую
    private static final Pattern ROOT = Pattern.compile(
            "(?:\\bfrom|\\bupdate|,)\\s+([A-Z]\\w*)\\s+(?:as\\s+)?([A-Za-z_]\\w*)", Pattern.CASE_INSENSITIVE);
    // join o.items i, left join fetch o.customer c
    private static final Pattern JOIN = Pattern.compile(
            "\\bjoin\\s+(?:fetch\\s+)?([A-Za-z_]\\w*)\\.([\\w.]+)\\s+(?:as\\s+)?([A-Za-z_]\\w*)", Pattern.CASE_INSENSITIVE);
    // o.customer.name; двоеточие перед именем - это параметр, точка - продолжение другого пути
    private static final Pattern PATH = Pattern.compile("(?<![:.\\w#])([A-Za-z_]\\w*)\\.([A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*)\\b(?!\\s*\\()");

    // Слова, которые могут стоять сразу после имени сущности и псевдонимом не являются
    private static final Set<String> KEYWORDS = Set.of(
            "where", "join", "left", "right", "inner", "outer", "cross", "full", "on", "set", "order", "group",
            "having", "fetch", "union", "limit", "with", "as", "and", "or");

    @Override
    public String code() {
        return RuleCodes.UNKNOWN_FIELD_IN_QUERY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует запросы JPQL в @Query и ищет обращения к полям, которых нет у сущности";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
                Optional<AnnotationExpr> query = Queries.find(method).filter(annotation -> !Queries.isNative(annotation));
                Optional<String> text = query.flatMap(Queries::text);
                if (text.isEmpty()) {
                    continue;
                }
                for (String missing : findMissing(text.get(), classes)) {
                    violations.add(violation(sourceFile, method,
                            "Запрос метода '" + method.getNameAsString() + "' обращается к полю '" + missing
                                    + "', которого у сущности нет: запрос не пройдет проверку при запуске приложения;"
                                    + " исправьте имя поля"));
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

    private Set<String> findMissing(String query, ProjectClasses classes) {
        String text = STRING_LITERAL.matcher(query).replaceAll("''");

        // Псевдоним -> класс сущности; null - псевдоним есть, но его класс неизвестен
        Map<String, ClassOrInterfaceDeclaration> aliases = new LinkedHashMap<>();
        Matcher root = ROOT.matcher(text);
        while (root.find()) {
            String alias = root.group(2);
            if (!KEYWORDS.contains(alias.toLowerCase(Locale.ROOT))) {
                aliases.put(alias, classes.find(root.group(1)).filter(type -> Annotations.has(type, ENTITY)).orElse(null));
            }
        }
        Matcher join = JOIN.matcher(text);
        while (join.find()) {
            ClassOrInterfaceDeclaration owner = aliases.get(join.group(1));
            aliases.put(join.group(3), owner == null ? null
                    : JpaProperties.typeOf(owner, Arrays.asList(join.group(2).split("\\.")), classes).orElse(null));
        }

        Set<String> missing = new LinkedHashSet<>();
        Matcher path = PATH.matcher(text);
        while (path.find()) {
            ClassOrInterfaceDeclaration owner = aliases.get(path.group(1));
            if (owner != null) {
                JpaProperties.findMissing(owner, Arrays.asList(path.group(2).split("\\.")), classes).ifPresent(missing::add);
            }
        }
        return missing;
    }
}
