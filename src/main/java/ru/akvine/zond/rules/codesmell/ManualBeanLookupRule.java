package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.SpringContainer;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Бин берется из ApplicationContext или BeanFactory вручную.
 * <p>
 * Это Service Locator вместо инверсии управления: класс сам ищет зависимости у контейнера, они не видны в
 * конструкторе, класс нельзя создать в тесте без контекста, а ошибка в имени или типе бина обнаружится при
 * вызове, а не при старте. Для всех случаев, ради которых обычно идут в контекст, у Spring есть внедрение:
 * новый экземпляр и необязательный бин - {@code ObjectProvider<T>}, выбор по имени - {@code Map<String, T>},
 * все реализации - {@code List<T>}.
 */
@Component
public class ManualBeanLookupRule extends AbstractRule {
    private static final String GET_BEAN = "getBean";
    private static final String BEAN = "Bean";
    private static final String MAIN = "main";
    private static final String GET_BEAN_ADVICE = "внедрите зависимость через конструктор; если нужен новый"
            + " экземпляр prototype-бина на каждый вызов либо бина может не быть - внедрите ObjectProvider<T>";
    private static final String ALL_BEANS_ADVICE = "внедрите List<T> либо Map<String, T> через конструктор -"
            + " Spring сам соберет в них все реализации";
    private static final String CREATION_ADVICE = "объект создается мимо контейнера, и зависимости в него"
            + " подставляются вручную; сделайте его бином (prototype, если нужен новый на каждый вызов) либо"
            + " передайте зависимости явно";

    // Совет зависит от того, что берут у контейнера
    private static final Map<String, String> LOOKUPS = Map.ofEntries(
            Map.entry(GET_BEAN, GET_BEAN_ADVICE),
            Map.entry("getBeanProvider", "внедрите ObjectProvider<T> через конструктор"),
            Map.entry("getBeansOfType", ALL_BEANS_ADVICE),
            Map.entry("getBeanNamesForType", ALL_BEANS_ADVICE),
            Map.entry("getBeansWithAnnotation", "дайте таким бинам общий интерфейс и внедрите List<T> либо"
                    + " соберите их при создании в BeanPostProcessor"),
            Map.entry("autowireBean", CREATION_ADVICE),
            Map.entry("createBean", CREATION_ADVICE),
            Map.entry("autowire", CREATION_ADVICE),
            Map.entry("initializeBean", CREATION_ADVICE),
            Map.entry("configureBean", CREATION_ADVICE));

    // event.getApplicationContext().getBean(...), context.getAutowireCapableBeanFactory().autowireBean(...)
    private static final Set<String> CONTAINER_GETTERS = Set.of(
            "getApplicationContext", "getAutowireCapableBeanFactory", "getBeanFactory", "getWebApplicationContext",
            "getRequiredWebApplicationContext");

    // applicationContext.getBean(...), beanFactory.getBean(...), ctx.getBean(...) - когда тип получателя неизвестен
    private static final Pattern CONTEXT_NAME = Pattern.compile(".*(context|beanfactory)$|^ctx$", Pattern.CASE_INSENSITIVE);

    @Override
    public String code() {
        return RuleCodes.MANUAL_BEAN_LOOKUP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет получение бинов вручную через ApplicationContext и BeanFactory: getBean(),"
                + " getBeansOfType(), autowireBean() и другие";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> LOOKUPS.containsKey(call.getNameAsString()))
                .filter(call -> call.getScope().filter(this::isContainer).isPresent())
                .filter(call -> !TestClasses.isInside(call) && !SpringContainer.isInsideInfrastructure(call)
                        && !isInsideBeanFactoryMethod(call))
                .map(call -> violation(sourceFile, call.getName(), message(call)))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    private boolean isContainer(Expression scope) {
        Expression receiver = Nodes.unwrap(scope);
        if (receiver.isMethodCallExpr()) {
            return CONTAINER_GETTERS.contains(receiver.asMethodCallExpr().getNameAsString());
        }
        // SpringContext.getBean(Foo.class) - статический доступ к контексту, сохраненному в поле класса
        if (isClassReference(receiver)) {
            return true;
        }
        return LocalTypes.isAnyOf(receiver, SpringContainer.TYPES,
                () -> CONTEXT_NAME.matcher(MethodCalls.receiverName(receiver)).matches());
    }

    // Имя с заглавной буквы, за которым не стоит переменная, - имя класса
    private boolean isClassReference(Expression receiver) {
        return receiver.isNameExpr()
                && Character.isUpperCase(receiver.asNameExpr().getNameAsString().charAt(0))
                && LocalTypes.findDeclaration(receiver).isEmpty();
    }

    // Метод с @Bean и main собирают приложение - им контейнер нужен по делу
    private boolean isInsideBeanFactoryMethod(MethodCallExpr call) {
        return call.findAncestor(MethodDeclaration.class)
                .filter(method -> Annotations.has(method, BEAN) || method.isStatic() && MAIN.equals(method.getNameAsString()))
                .isPresent();
    }

    private String message(MethodCallExpr call) {
        String name = call.getNameAsString();
        String advice = LOOKUPS.get(name);
        // getBean(beanName) с именем, вычисленным во время работы, - выбор реализации
        if (GET_BEAN.equals(name) && !call.getArguments().isEmpty() && isComputed(call.getArgument(0))) {
            advice = "выбор реализации по имени делается через внедренную Map<String, T>: Spring сам сложит в нее"
                    + " все реализации, ключ - имя бина";
        }
        return "'" + Nodes.text(call) + "': бин берется из контейнера вручную, а не внедряется - это Service"
                + " Locator вместо инверсии управления: зависимость не видна в конструкторе, класс не создать в"
                + " тесте без контекста, а ошибка в имени или типе бина обнаружится только при вызове; " + advice;
    }

    private boolean isComputed(Expression argument) {
        Expression value = Nodes.unwrap(argument);
        return !value.isStringLiteralExpr() && !value.isClassExpr() && !value.isFieldAccessExpr();
    }
}
