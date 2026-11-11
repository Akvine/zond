package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.Queries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code @Query("select o from Order o left join fetch o.client where o.status = :status")} с типом Page и без
 * countQuery: запрос для подсчета строк Spring Data выводит из основного и оставляет в нем те же соединения.
 * LEFT JOIN к одной записи на число строк не влияет - подсчет делает лишнюю работу на каждой странице.
 */
@Component
public class PageCountWithJoinsRule extends AbstractRule implements ProjectRule {
    private static final String PAGE = "Page";
    private static final String COUNT_QUERY = "countQuery";
    // from Order o, from Order as o
    private static final Pattern ROOT = Pattern.compile(
            "\\bfrom\\s+(\\w+)\\s+(?:as\\s+)?(\\w+)", Pattern.CASE_INSENSITIVE);
    // left join o.client c, left outer join fetch o.client
    private static final Pattern LEFT_JOIN = Pattern.compile(
            "\\bleft\\s+(?:outer\\s+)?join\\s+(?:fetch\\s+)?(\\w+)\\.(\\w+)"
                    + "(?:\\s+(?:as\\s+)?(?!(?:on|where|left|right|inner|join|order|group|having)\\b)(\\w+))?",
            Pattern.CASE_INSENSITIVE);
    // С distinct, group by и union число строк от соединений зависит
    private static final Pattern RESHAPING = Pattern.compile(
            "\\b(distinct|group\\s+by|union)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHERE = Pattern.compile("\\bwhere\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORDER_BY = Pattern.compile("\\border\\s+by\\b", Pattern.CASE_INSENSITIVE);
    private static final Set<String> REFERENCE_RELATIONS = Set.of("ManyToOne", "OneToOne");
    private static final Set<String> COLLECTION_RELATIONS = Set.of("OneToMany", "ManyToMany", "ElementCollection");

    // Связь к одной записи, к нескольким либо сущность в проекте не нашлась
    private enum Kind { REFERENCE, COLLECTION, UNKNOWN }

    @Override
    public String code() {
        return RuleCodes.PAGE_COUNT_WITH_JOINS_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует репозитории и ищет JPQL-запросы с типом Page без countQuery, в которых подсчет строк"
                + " унаследует LEFT JOIN, не влияющий на их число";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
                if (PAGE.equals(LocalTypes.typeName(method.getType()))) {
                    check(sourceFile, method, classes, violations);
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

    // Часть баз сама убирает из плана LEFT JOIN по уникальному ключу, если его колонки не нужны
    @Override
    public Confidence confidence() {
        return Confidence.PROBABLE;
    }

    private void check(SourceFile sourceFile, MethodDeclaration method, ProjectClasses classes,
                       List<Violation> violations) {
        Optional<AnnotationExpr> query = Queries.find(method)
                .filter(found -> !Queries.isNative(found) && Queries.member(found, COUNT_QUERY).isEmpty());
        Optional<String> text = query.flatMap(Queries::text);
        if (text.isEmpty() || RESHAPING.matcher(text.get()).find()) {
            return;
        }
        List<String> joins = new ArrayList<>();
        boolean resolved = collectIdleJoins(text.get(), classes, joins);
        if (joins.isEmpty()) {
            return;
        }
        Violation violation = violation(sourceFile, method,
                "Запрос метода '" + method.getNameAsString() + "' возвращает Page без countQuery: строки для"
                        + " каждой страницы Spring Data посчитает тем же запросом вместе с LEFT JOIN по "
                        + String.join(", ", joins) + ", а на число строк это соединение не влияет; задайте"
                        + " countQuery без него");
        violations.add(resolved ? violation : violation.withConfidence(Confidence.SUSPICION));
    }

    /**
     * Собирает соединения, без которых подсчет дал бы то же число.
     *
     * @return true, если про каждое из них по сущности видно, что это связь к одной записи
     */
    private boolean collectIdleJoins(String text, ProjectClasses classes, List<String> joins) {
        Matcher root = ROOT.matcher(text);
        if (!root.find()) {
            return false;
        }
        String condition = condition(text);
        boolean resolved = true;
        Matcher join = LEFT_JOIN.matcher(text);
        while (join.find()) {
            String alias = join.group(3);
            // left join o.client c where c.name = :name - от соединения зависит, какие строки останутся
            if (alias != null && Pattern.compile("\\b" + Pattern.quote(alias) + "\\b").matcher(condition).find()) {
                continue;
            }
            Kind kind = join.group(1).equals(root.group(2))
                    ? kind(root.group(1), join.group(2), classes)
                    : Kind.UNKNOWN;
            // Соединение с коллекцией размножает строки: без него подсчет дал бы другое число
            if (kind == Kind.COLLECTION) {
                continue;
            }
            resolved &= kind == Kind.REFERENCE;
            joins.add("'" + join.group(1) + "." + join.group(2) + "'");
        }
        return resolved;
    }

    // Все, что между where и order by: условия, от которых зависит число строк
    private String condition(String text) {
        Matcher where = WHERE.matcher(text);
        if (!where.find()) {
            return "";
        }
        Matcher order = ORDER_BY.matcher(text);
        return order.find(where.end()) ? text.substring(where.end(), order.start()) : text.substring(where.end());
    }

    private Kind kind(String entity, String field, ProjectClasses classes) {
        Optional<FieldDeclaration> relation = classes.find(entity)
                .flatMap(type -> findField(type, field, classes));
        if (relation.filter(found -> Annotations.hasAny(found, REFERENCE_RELATIONS)).isPresent()) {
            return Kind.REFERENCE;
        }
        return relation.filter(found -> Annotations.hasAny(found, COLLECTION_RELATIONS)).isPresent()
                ? Kind.COLLECTION
                : Kind.UNKNOWN;
    }

    // Поле сущности или ее предка
    private Optional<FieldDeclaration> findField(ClassOrInterfaceDeclaration type, String field,
                                                 ProjectClasses classes) {
        Optional<ClassOrInterfaceDeclaration> current = Optional.of(type);
        while (current.isPresent()) {
            Optional<FieldDeclaration> found = current.get().getFieldByName(field);
            if (found.isPresent()) {
                return found;
            }
            current = classes.parent(current.get());
        }
        return Optional.empty();
    }
}
