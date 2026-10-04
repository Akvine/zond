package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Queries;
import ru.akvine.zond.rules.support.StringLiterals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CheckQueryParameterMismatchRule extends AbstractRule {
    private static final String PARAM = "Param";

    // :name, но не ::text (приведение типа в PostgreSQL) и не :#{...} (SpEL)
    private static final Pattern NAMED_PARAMETER = Pattern.compile("(?<![:\\w]):([A-Za-z_]\\w*)");
    // Не больше четырех цифр: номер заведомо помещается в int
    private static final Pattern POSITIONAL_PARAMETER = Pattern.compile("\\?(\\d{1,4})");
    private static final String IN_PREFIX = "\\bin\\s*\\(?\\s*";
    private static final String EQUALS_PREFIX = "(=|<>|!=)\\s*";

    // Эти параметры Spring Data обрабатывает сам - в текст запроса они не подставляются
    private static final Set<String> SPECIAL_TYPES = Set.of("Pageable", "Sort", "PageRequest", "Limit");
    private static final Set<String> COLLECTION_TYPES = Set.of(
            "Collection", "List", "Set", "Iterable", "ArrayList", "HashSet", "LinkedList", "SortedSet", "TreeSet");
    private static final String ARRAY_SUFFIX = "[]";

    @Override
    public String code() {
        return RuleCodes.CHECK_QUERY_PARAMETER_MISMATCH_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет несовпадение параметров @Query и метода: имена, номера, коллекция в IN";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodDeclaration method : sourceFile.unit().findAll(MethodDeclaration.class)) {
            Optional<String> query = Queries.find(method).flatMap(Queries::text);
            if (query.isEmpty()) {
                continue;
            }
            for (String problem : findProblems(query.get(), method)) {
                violations.add(violation(sourceFile, method,
                        "Запрос метода '" + method.getNameAsString() + "': " + problem));
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

    private List<String> findProblems(String query, MethodDeclaration method) {
        // Имя в запросе -> параметр метода; Pageable и Sort в запрос не подставляются
        Map<String, Parameter> parameters = new LinkedHashMap<>();
        for (Parameter parameter : method.getParameters()) {
            if (!SPECIAL_TYPES.contains(LocalTypes.typeName(parameter.getType()))) {
                parameters.put(nameInQuery(parameter), parameter);
            }
        }

        List<String> problems = new ArrayList<>();
        Matcher named = NAMED_PARAMETER.matcher(query);
        Set<String> used = new LinkedHashSet<>();
        while (named.find()) {
            used.add(named.group(1));
        }
        for (String name : used) {
            if (!parameters.containsKey(name)) {
                problems.add("параметра ':" + name + "' нет среди параметров метода - запрос упадет при вызове;"
                        + " проверьте имя и @Param");
            }
        }
        // #{#name} - обращение к параметру из SpEL
        parameters.forEach((name, parameter) -> {
            if (Annotations.has(parameter, PARAM) && !used.contains(name) && !query.contains("#" + name)) {
                problems.add("параметр @Param(\"" + name + "\") в запросе не используется - условие по нему"
                        + " забыли либо в имени опечатка");
            }
        });

        Matcher positional = POSITIONAL_PARAMETER.matcher(query);
        while (positional.find()) {
            int number = Integer.parseInt(positional.group(1));
            if (number < 1 || number > parameters.size()) {
                problems.add("параметра '?" + number + "' нет: у метода " + parameters.size() + " параметров");
            }
        }

        parameters.forEach((name, parameter) -> describeCollectionMisuse(query, name, parameter).ifPresent(problems::add));
        return problems;
    }

    // IN ждет набор значений, а сравнение через = - одно значение
    private Optional<String> describeCollectionMisuse(String query, String name, Parameter parameter) {
        String type = LocalTypes.typeName(parameter.getType());
        boolean isCollection = COLLECTION_TYPES.contains(type) || type.endsWith(ARRAY_SUFFIX);
        String reference = ":" + Pattern.quote(name) + "\\b";
        if (!isCollection && matches(query, IN_PREFIX + reference)) {
            return Optional.of("параметр ':" + name + "' стоит в IN, а объявлен как " + type + ": в IN передается"
                    + " набор значений; объявите параметр коллекцией");
        }
        if (isCollection && matches(query, EQUALS_PREFIX + reference)) {
            return Optional.of("параметр ':" + name + "' - коллекция, а сравнивается через '=': запрос упадет;"
                    + " используйте IN");
        }
        return Optional.empty();
    }

    private boolean matches(String query, String pattern) {
        return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(query).find();
    }

    // @Param("id") Long userId -> id; без @Param - имя параметра
    private String nameInQuery(Parameter parameter) {
        return Annotations.find(parameter, PARAM)
                .filter(AnnotationExpr::isSingleMemberAnnotationExpr)
                .flatMap(annotation -> StringLiterals.textOf(annotation.asSingleMemberAnnotationExpr().getMemberValue()))
                .orElse(parameter.getNameAsString());
    }
}
