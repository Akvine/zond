package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
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
 */
@Component
public class ConstantHelperPerCallRule extends AbstractRule {
    private static final String PATTERN = "Pattern";
    private static final String COMPILE = "compile";
    private static final String FORMATTER = "DateTimeFormatter";
    private static final String OF_PATTERN = "ofPattern";
    private static final Pattern SETTINGS_TYPE = Pattern.compile("(Serializer|Deserializer|Module|Converter|Formatter)$");
    private static final Pattern SETUP_METHOD = Pattern.compile("^(init|configure|setup|setUp|register|customize)");

    @Override
    public String code() {
        return RuleCodes.CONSTANT_HELPER_PER_CALL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет Pattern.compile и DateTimeFormatter.ofPattern с постоянным шаблоном, которые"
                + " выполняются при каждом вызове метода";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            boolean pattern = MethodCalls.isCallOn(call, PATTERN, COMPILE);
            boolean formatter = MethodCalls.isCallOn(call, FORMATTER, OF_PATTERN);
            if (!pattern && !formatter || !hasConstantTemplate(call) || !isCreatedOnEveryCall(call)) {
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

    // Pattern.compile("\\d+"), Pattern.compile("a|b", Pattern.CASE_INSENSITIVE), ofPattern("dd.MM", Locale.ROOT)
    private boolean hasConstantTemplate(MethodCallExpr call) {
        return !call.getArguments().isEmpty() && call.getArgument(0).isStringLiteralExpr()
                && call.getArguments().stream().skip(1).allMatch(argument -> argument.isFieldAccessExpr());
    }

    // Поле, статический блок, конструктор, @Bean и @PostConstruct выполняются один раз - там создавать уместно
    private boolean isCreatedOnEveryCall(MethodCallExpr call) {
        Optional<Node> callable = Nodes.enclosingCallable(call);
        boolean method = callable.filter(node -> node instanceof MethodDeclaration).isPresent()
                || call.findAncestor(MethodDeclaration.class).isPresent();
        return method && !CodeContexts.isStartup(call) && !isSetup(call) && !TestClasses.isInside(call);
    }

    /**
     * new LocalDateSerializer(DateTimeFormatter.ofPattern("yyyy-MM-dd")) в initJavaTimeModule(): объект настройки
     * создают один раз, и форматтер остается жить внутри него
     */
    private boolean isSetup(MethodCallExpr call) {
        boolean partOfSettings = call.getParentNode()
                .filter(parent -> parent instanceof ObjectCreationExpr creation
                        && SETTINGS_TYPE.matcher(creation.getType().getNameAsString()).find())
                .isPresent();
        return partOfSettings || call.findAncestor(MethodDeclaration.class)
                .filter(method -> SETUP_METHOD.matcher(method.getNameAsString()).find())
                .isPresent();
    }
}
