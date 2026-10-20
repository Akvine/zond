package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ConfigProperty;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.Queries;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Потоковое чтение без размера выборки: метод репозитория возвращает Stream, jdbcTemplate.queryForStream(...).
 * Без fetch size драйверы PostgreSQL и MySQL забирают в память весь результат еще до первой строки, и Stream
 * от этого не спасает.
 */
@Component
public class StreamQueryWithoutFetchSizeRule extends AbstractContextRule {
    private static final String STREAM = "Stream";
    private static final String QUERY_HINTS = "QueryHints";
    // HINT_FETCH_SIZE, "org.hibernate.fetchSize", "jakarta.persistence.query.fetchSize"
    private static final Pattern FETCH_SIZE_HINT = Pattern.compile("(?i)fetch_?size");
    // spring.jpa.properties.hibernate.jdbc.fetch_size, spring.jdbc.template.fetch-size
    private static final Pattern FETCH_SIZE_PROPERTY = Pattern.compile("(?i).*fetch[-_]?size$");
    private static final Pattern JDBC_TEMPLATE = Pattern.compile(".*jdbcTemplate$", Pattern.CASE_INSENSITIVE);
    private static final String QUERY_FOR_STREAM = "queryForStream";
    private static final String SET_FETCH_SIZE = "setFetchSize";

    @Override
    public String code() {
        return RuleCodes.STREAM_QUERY_WITHOUT_FETCH_SIZE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет потоковое чтение из БД (Stream в репозитории, queryForStream) без размера выборки";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        // Размер выборки задан для всего приложения настройкой либо в коде шаблона
        boolean configured = context.configFiles().stream()
                .flatMap(file -> file.properties().stream())
                .map(ConfigProperty::key)
                .anyMatch(key -> FETCH_SIZE_PROPERTY.matcher(key).matches());
        if (configured) {
            return List.of();
        }
        boolean templateConfigured = context.sources().stream()
                .flatMap(sourceFile -> sourceFile.unit().findAll(MethodCallExpr.class).stream())
                .anyMatch(call -> SET_FETCH_SIZE.equals(call.getNameAsString()));

        ProjectClasses classes = ProjectClasses.of(context.sources());
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : context.sources()) {
            for (ClassOrInterfaceDeclaration type : sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class)) {
                if (!type.isInterface() || TestClasses.isInside(type) || !Queries.isSpringDataRepository(type, classes)) {
                    continue;
                }
                for (MethodDeclaration method : type.getMethods()) {
                    if (STREAM.equals(LocalTypes.typeName(method.getType())) && !hasFetchSizeHint(method)) {
                        violations.add(violation(sourceFile.path(), line(method), "Метод '" + method.getNameAsString()
                                + "' возвращает Stream без размера выборки: драйверы PostgreSQL и MySQL без fetch size"
                                + " забирают в память весь результат еще до первой строки; добавьте"
                                + " @QueryHints(@QueryHint(name = HINT_FETCH_SIZE, value = \"500\")) либо задайте"
                                + " spring.jpa.properties.hibernate.jdbc.fetch_size"));
                    }
                }
            }
            if (templateConfigured) {
                continue;
            }
            for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
                if (QUERY_FOR_STREAM.equals(call.getNameAsString()) && call.getScope()
                        .filter(scope -> JDBC_TEMPLATE.matcher(MethodCalls.receiverName(scope)).matches()).isPresent()
                        && !TestClasses.isInside(call)) {
                    violations.add(violation(sourceFile.path(), call.getBegin().map(position -> position.line).orElse(1),
                            "'" + MethodCalls.receiverName(call.getScope().get()) + ".queryForStream(...)' без размера"
                                    + " выборки: драйверы PostgreSQL и MySQL без fetch size забирают в память весь"
                                    + " результат еще до первой строки; задайте jdbcTemplate.setFetchSize(...) либо"
                                    + " spring.jdbc.template.fetch-size"));
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

    private boolean hasFetchSizeHint(MethodDeclaration method) {
        return Annotations.find(method, QUERY_HINTS)
                .filter(hints -> FETCH_SIZE_HINT.matcher(hints.toString().toLowerCase(Locale.ROOT)).find())
                .isPresent();
    }

    private int line(MethodDeclaration method) {
        return method.getName().getBegin().map(position -> position.line).orElse(1);
    }
}
