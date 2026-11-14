package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LiteralStringValueExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.ScanContext;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.TestPaths;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractContextRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.files.SpringVersions;
import ru.akvine.zond.rules.support.JdbcBatches;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code batchUpdate(sql, Map[])} и {@code batchUpdate(sql, List<Object[]>)} передают значения без типов SQL.
 * Для обычного значения Spring берет тип из класса объекта, а для {@code null} спрашивает его у драйвера через
 * {@code ParameterMetaData.getParameterType}. У PostgreSQL, Oracle и SQL Server это обращение к базе на каждое
 * пустое значение, и пакет с пустыми колонками вставляется в десятки раз медленнее.
 * <p>
 * Spring Framework 6.1.2 (Spring Boot 3.2.1) обходит запрос типа для PostgreSQL и SQL Server сам; в более ранних
 * версиях помогает настройка {@code spring.jdbc.getParameterType.ignore=true} либо явные типы.
 */
@Component
public class UntypedBatchParametersRule extends AbstractContextRule {
    private static final String IGNORE_PROPERTY = "getParameterType.ignore";

    // С этой версии Spring Boot запрос типа у PostgreSQL и SQL Server не выполняется
    private static final int FIXED_MAJOR = 3;
    private static final int FIXED_MINOR = 2;
    private static final int FIXED_PATCH = 1;

    private static final Pattern MAP_ARRAY = Pattern.compile("(java\\.util\\.)?\\w*Map(<.*>)?\\[]");
    private static final Pattern MAP_COLLECTION =
            Pattern.compile("(java\\.util\\.)?\\w*(List|Collection|Set)<\\s*(java\\.util\\.)?\\w*Map\\b.*");
    private static final Pattern OBJECT_ARRAYS =
            Pattern.compile("(java\\.util\\.)?\\w*(List|Collection)<\\s*Object\\[]\\s*>");
    private static final Pattern PARAMETER_SOURCES = Pattern.compile(".*SqlParameterSource\\b.*");

    private static final String CREATE_BATCH = "createBatch";
    private static final String MAP_SOURCE = "MapSqlParameterSource";
    private static final String ADD_VALUE = "addValue";
    private static final int TYPED_ADD_VALUE_ARGUMENTS = 3;
    // Признаки того, что типы заданы явно
    private static final List<String> TYPED_MARKERS = List.of("registerSqlType", "SqlParameterValue", "registerTypeName");

    private static final String NAMED_ADVICE = "передавайте типы - MapSqlParameterSource.addValue(имя, значение, тип)";
    private static final String POSITIONAL_ADVICE = "передавайте типы - batchUpdate(sql, batchArgs, argTypes)";

    @Override
    public String code() {
        return RuleCodes.UNTYPED_BATCH_PARAMETERS_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет batchUpdate со значениями без типов SQL (Map[], List<Object[]>,"
                + " MapSqlParameterSource без типа): на каждый NULL Spring запрашивает тип параметра у драйвера";
    }

    @Override
    public List<Violation> checkContext(ScanContext context) {
        List<SpringVersions.Version> versions = SpringVersions.boot(context.textFiles());
        boolean isFixed = !versions.isEmpty()
                && versions.stream().allMatch(version -> version.isAtLeast(FIXED_MAJOR, FIXED_MINOR, FIXED_PATCH));
        if (isFixed || isIgnoreConfigured(context)) {
            return List.of();
        }

        List<Violation> violations = new ArrayList<>();
        for (SourceFile source : context.sources()) {
            if (TestPaths.isTest(source.path())) {
                continue;
            }
            for (MethodCallExpr call : source.unit().findAll(MethodCallExpr.class)) {
                if (!JdbcBatches.isBatchUpdate(call) || call.getArguments().size() < 2) {
                    continue;
                }
                findAdvice(call).ifPresent(advice -> {
                    Violation violation = violation(source.path(), line(call),
                            "'" + call.getScope().get() + ".batchUpdate(...)' получает значения без типов SQL:"
                                    + " для каждого NULL Spring спрашивает тип параметра у драйвера"
                                    + " (ParameterMetaData.getParameterType), а у PostgreSQL, Oracle и SQL Server это"
                                    + " обращение к базе на каждое пустое значение; задайте"
                                    + " spring.jdbc.getParameterType.ignore=true (файл spring.properties или -D) либо "
                                    + advice + ". Spring Boot 3.2.1+ для PostgreSQL и SQL Server делает это сам");
                    // Версия Spring в файлах сборки не названа - действует ли обход, по проекту не видно
                    violations.add(versions.isEmpty() ? violation.withConfidence(Confidence.SUSPICION) : violation);
                });
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
        return ErrorType.PERFORMANCE;
    }

    // Настройка задается в spring.properties, параметром -D в Dockerfile и манифестах либо из кода
    private boolean isIgnoreConfigured(ScanContext context) {
        boolean inConfigs = context.configFiles().stream()
                .flatMap(config -> config.properties().stream())
                .anyMatch(property -> property.key().contains(IGNORE_PROPERTY)
                        || property.value() != null && property.value().contains(IGNORE_PROPERTY));
        boolean inTextFiles = context.textFiles().stream()
                .anyMatch(file -> file.lines().stream().anyMatch(line -> line.contains(IGNORE_PROPERTY)));
        return inConfigs || inTextFiles || context.sources().stream()
                .anyMatch(source -> source.unit().findAll(LiteralStringValueExpr.class).stream()
                        .anyMatch(literal -> literal.getValue().contains(IGNORE_PROPERTY)));
    }

    /**
     * @return как передать типы, если значения пакета уходят без них
     */
    private Optional<String> findAdvice(MethodCallExpr call) {
        Expression values = Nodes.unwrap(call.getArgument(1));
        Optional<MethodDeclaration> method = call.findAncestor(MethodDeclaration.class);
        boolean isTyped = method
                .map(Node::toString)
                .filter(text -> TYPED_MARKERS.stream().anyMatch(text::contains))
                .isPresent();
        if (isTyped) {
            return Optional.empty();
        }

        Optional<String> type = declaredType(values);
        // batchUpdate(sql, List<Object[]>) - без третьего аргумента с типами
        if (call.getArguments().size() == 2 && type.filter(name -> OBJECT_ARRAYS.matcher(name).matches()).isPresent()) {
            return Optional.of(POSITIONAL_ADVICE);
        }
        if (type.filter(name -> MAP_ARRAY.matcher(name).matches()).isPresent() || isBatchOfMaps(values)
                || LocalTypes.findInitializer(values).filter(this::isBatchOfMaps).isPresent()) {
            return Optional.of(NAMED_ADVICE);
        }
        boolean isSources = type.filter(name -> PARAMETER_SOURCES.matcher(name).matches()).isPresent();
        return isSources && method.filter(this::buildsUntypedSources).isPresent()
                ? Optional.of(NAMED_ADVICE)
                : Optional.empty();
    }

    // SqlParameterSourceUtils.createBatch(rows), где rows - карты: из бинов типы берутся по свойствам
    private boolean isBatchOfMaps(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (!value.isMethodCallExpr() || !CREATE_BATCH.equals(value.asMethodCallExpr().getNameAsString())
                || value.asMethodCallExpr().getArguments().size() != 1) {
            return false;
        }
        return declaredType(Nodes.unwrap(value.asMethodCallExpr().getArgument(0)))
                .filter(name -> MAP_ARRAY.matcher(name).matches() || MAP_COLLECTION.matcher(name).matches())
                .isPresent();
    }

    // new MapSqlParameterSource(map), new MapSqlParameterSource().addValue("name", value) - и ни одного типа
    private boolean buildsUntypedSources(MethodDeclaration method) {
        boolean createsMapSources = method.findAll(ObjectCreationExpr.class).stream()
                .anyMatch(creation -> MAP_SOURCE.equals(creation.getType().getNameAsString()));
        boolean hasTypedValue = method.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> ADD_VALUE.equals(call.getNameAsString())
                        && call.getArguments().size() >= TYPED_ADD_VALUE_ARGUMENTS);
        return createsMapSources && !hasTypedValue;
    }

    // Тип так, как он записан в объявлении: вместе с параметрами и скобками массива
    private Optional<String> declaredType(Expression value) {
        if (value.isArrayCreationExpr()) {
            // new Map[] {} - пустой пакет: значений в нем нет
            boolean isEmpty = value.asArrayCreationExpr().getInitializer()
                    .filter(initializer -> initializer.getValues().isEmpty())
                    .isPresent();
            return isEmpty
                    ? Optional.empty()
                    : Optional.of(value.asArrayCreationExpr().getElementType().asString() + "[]");
        }
        if (value.isCastExpr()) {
            return Optional.of(value.asCastExpr().getType().asString());
        }
        if (!value.isNameExpr()) {
            return Optional.empty();
        }
        String name = value.asNameExpr().getNameAsString();
        Optional<Node> declaration = LocalTypes.findDeclaration(value, name)
                .or(() -> LocalTypes.findField(value, name).map(Node.class::cast));
        return declaration.flatMap(node -> {
            if (node instanceof Parameter parameter) {
                return Optional.of(parameter.getType().asString() + (parameter.isVarArgs() ? "[]" : ""));
            }
            return node instanceof VariableDeclarator variable
                    ? Optional.of(variable.getType().asString())
                    : Optional.empty();
        });
    }

    private int line(Node node) {
        return node.getBegin().map(position -> position.line).orElse(1);
    }
}
