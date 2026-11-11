package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.CodeContexts;
import ru.akvine.zond.rules.support.Loops;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Pattern.compile("\\d+") и DateTimeFormatter.ofPattern("dd.MM.yyyy") в теле метода: шаблон задан строкой
 * и не меняется, но разбирается заново при каждом вызове. Оба объекта неизменяемы и потокобезопасны - их
 * место в private static final.
 * <p>
 * new SimpleDateFormat("dd.MM.yyyy") в теле метода - тот же лишний разбор, но в поле его вынести нельзя:
 * объект не потокобезопасен. Его заменяют на DateTimeFormatter в константе.
 */
@Component
public class ConstantHelperPerCallRule extends AbstractRule {
    private static final String PATTERN = "Pattern";
    private static final String COMPILE = "compile";
    private static final String FORMATTER = "DateTimeFormatter";
    private static final String OF_PATTERN = "ofPattern";
    private static final String LEGACY_FORMAT = "SimpleDateFormat";
    // mapper.setDateFormat(new SimpleDateFormat(...)): форматтер остается жить в настроенном объекте
    private static final Pattern SETTING_CALL = Pattern.compile(
            "^(set[A-Z]\\w*|dateFormat|simpleDateFormat|registerCustomEditor)$");
    private static final Pattern SETTINGS_TYPE = Pattern.compile(
            "(Serializer|Deserializer|Module|Converter|Formatter|Editor)$");
    private static final Pattern SETUP_METHOD = Pattern.compile("^(init|configure|setup|setUp|register|customize)");

    @Override
    public String code() {
        return RuleCodes.CONSTANT_HELPER_PER_CALL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет Pattern.compile, DateTimeFormatter.ofPattern и new SimpleDateFormat"
                + " с постоянным шаблоном, которые выполняются при каждом вызове метода";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            boolean pattern = MethodCalls.isCallOn(call, PATTERN, COMPILE);
            boolean formatter = MethodCalls.isCallOn(call, FORMATTER, OF_PATTERN);
            if (!pattern && !formatter || !hasConstantTemplate(call.getArguments()) || !isCreatedOnEveryCall(call)) {
                continue;
            }
            // Шаблон в цикле показывает jr:239 - там потеря больше, и совет тот же
            if (pattern && Loops.enclosingIteration(call).isPresent()) {
                continue;
            }
            violations.add(violation(sourceFile, call,
                    "'" + Nodes.text(call) + "' выполняется при каждом вызове метода: шаблон постоянный, а"
                            + " разбирается каждый раз заново; объект неизменяемый и потокобезопасный - вынесите"
                            + " его в private static final")
                    .withLevel(pattern ? ErrorLevel.MINOR : ErrorLevel.INFO));
        }
        checkLegacyFormats(sourceFile, violations);
        violations.sort((left, right) -> Integer.compare(left.line(), right.line()));
        return violations;
    }

    private void checkLegacyFormats(SourceFile sourceFile, List<Violation> violations) {
        for (ObjectCreationExpr creation : sourceFile.unit().findAll(ObjectCreationExpr.class)) {
            // В цикле дорогой объект показывает отдельное правило
            if (LEGACY_FORMAT.equals(creation.getType().getNameAsString())
                    && hasConstantTemplate(creation.getArguments())
                    && isCreatedOnEveryCall(creation) && Loops.enclosingIteration(creation).isEmpty()) {
                violations.add(violation(sourceFile, creation,
                        "'" + Nodes.text(creation) + "' создается при каждом вызове метода: шаблон постоянный, а"
                                + " разбирается каждый раз заново, и вместе с ним создается календарь. В поле"
                                + " SimpleDateFormat вынести нельзя - он не потокобезопасен; замените его на"
                                + " DateTimeFormatter в private static final")
                        .withLevel(ErrorLevel.INFO));
            }
        }
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    // Pattern.compile("\\d+"), Pattern.compile("a|b", Pattern.CASE_INSENSITIVE), ofPattern("dd.MM", Locale.ROOT)
    private boolean hasConstantTemplate(List<Expression> arguments) {
        return !arguments.isEmpty() && arguments.get(0).isStringLiteralExpr()
                && arguments.stream().skip(1).allMatch(Expression::isFieldAccessExpr);
    }

    // Поле, статический блок, конструктор, @Bean и @PostConstruct выполняются один раз - там создавать уместно
    private boolean isCreatedOnEveryCall(Expression call) {
        Optional<Node> callable = Nodes.enclosingCallable(call);
        boolean method = callable.filter(node -> node instanceof MethodDeclaration).isPresent()
                || call.findAncestor(MethodDeclaration.class).isPresent();
        return method && !CodeContexts.isStartup(call) && !isSetup(call) && !TestClasses.isInside(call);
    }

    /**
     * new LocalDateSerializer(DateTimeFormatter.ofPattern("yyyy-MM-dd")) в initJavaTimeModule(): объект настройки
     * создают один раз, и форматтер остается жить внутри него
     */
    private boolean isSetup(Expression call) {
        boolean partOfSettings = call.getParentNode()
                .filter(parent -> parent instanceof ObjectCreationExpr creation
                        && SETTINGS_TYPE.matcher(creation.getType().getNameAsString()).find()
                        || parent instanceof MethodCallExpr setting
                        && SETTING_CALL.matcher(setting.getNameAsString()).find()
                        && setting.getArguments().stream().anyMatch(argument -> argument == call))
                .isPresent();
        return partOfSettings || call.findAncestor(MethodDeclaration.class)
                .filter(method -> SETUP_METHOD.matcher(method.getNameAsString()).find())
                .isPresent();
    }
}
