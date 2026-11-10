package ru.akvine.zond.rules.resources;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.StreamChains;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Самодельный кеш на мягких ссылках. Когда JVM не хватает памяти, сборщик очищает все SoftReference разом:
 * кеш пустеет в самый неподходящий момент, и вся нагрузка уходит в базу. До этого момента мягкие ссылки
 * занимают кучу до упора и удлиняют паузы сборки. SoftReference в роли ключа не работает вовсе - у нее нет
 * своих equals и hashCode.
 */
@Component
public class SoftReferenceCacheRule extends AbstractRule {
    private static final String SOFT_REFERENCE = "SoftReference";
    private static final String SOFT_VALUES = "softValues";
    private static final String REFERENCE_MAP = "ConcurrentReferenceHashMap";
    private static final String WEAK = "WEAK";
    // Построители кешей Guava и Caffeine
    private static final Set<String> CACHE_BUILDERS = Set.of("newBuilder", "from");
    private static final Set<String> MAP_TYPES = Set.of(
            "Map", "HashMap", "ConcurrentHashMap", "ConcurrentMap", "LinkedHashMap", "TreeMap", "Hashtable",
            "WeakHashMap", "SortedMap", "NavigableMap");
    private static final String ADVICE = "; используйте кеш с явным пределом размера и сроком жизни записей:"
            + " Caffeine с maximumSize(...) и expireAfterWrite(...)";
    private static final String UNPREDICTABLE = ": когда JVM не хватает памяти, сборщик очищает все мягкие ссылки"
            + " разом - кеш опустеет в самый неподходящий момент, и вся нагрузка уйдет в базу или во внешний сервис,"
            + " а до этого момента мягкие ссылки занимают кучу до упора и удлиняют паузы сборки мусора";

    @Override
    public String code() {
        return RuleCodes.SOFT_REFERENCE_CACHE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет самодельные кеши на SoftReference: мягкие ссылки ключом карты, значениями"
                + " карты и в полях, softValues() и ConcurrentReferenceHashMap";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (VariableDeclarator variable : sourceFile.unit().findAll(VariableDeclarator.class)) {
            if (TestClasses.isInside(variable)) {
                continue;
            }
            boolean field = variable.getParentNode().filter(parent -> parent instanceof FieldDeclaration).isPresent();
            String name = variable.getNameAsString();
            if (hasSoftKey(variable.getType())) {
                violations.add(violation(sourceFile, variable,
                        "SoftReference - ключ карты '" + name + "': у ссылки нет своих equals и hashCode, поэтому"
                                + " две ссылки на один и тот же объект не равны - запись, положенную по одной ссылке,"
                                + " по другой не найти. А когда сборщик очистит ссылки, записи останутся в карте"
                                + " навсегда с пустыми ключами" + ADVICE));
            } else if (field && (isSoft(variable.getType()) || hasSoftValue(variable.getType()))) {
                violations.add(violation(sourceFile, variable,
                        "Поле '" + name + "' - самодельный кеш на SoftReference" + UNPREDICTABLE + ADVICE)
                        .withLevel(ErrorLevel.MINOR));
            }
        }

        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            // CacheBuilder.newBuilder().softValues(), Caffeine.newBuilder().softValues()
            boolean builder = SOFT_VALUES.equals(call.getNameAsString()) && call.getArguments().isEmpty()
                    && StreamChains.callsBefore(call).stream()
                    .anyMatch(step -> CACHE_BUILDERS.contains(step.getNameAsString()));
            if (builder && !TestClasses.isInside(call)) {
                violations.add(violation(sourceFile, call,
                        "softValues(): значения кеша хранятся по мягким ссылкам" + UNPREDICTABLE
                                + "; задайте кешу предел размера и срок жизни записей: maximumSize(...),"
                                + " expireAfterWrite(...)").withLevel(ErrorLevel.MINOR));
            }
        }

        for (ObjectCreationExpr creation : sourceFile.unit().findAll(ObjectCreationExpr.class)) {
            // У ConcurrentReferenceHashMap ссылки мягкие, если явно не задан ReferenceType.WEAK
            boolean soft = REFERENCE_MAP.equals(creation.getType().getNameAsString())
                    && creation.getArguments().stream().noneMatch(argument -> argument.toString().contains(WEAK));
            boolean stored = creation.findAncestor(FieldDeclaration.class).isPresent()
                    || creation.getParentNode().filter(parent -> parent instanceof AssignExpr).isPresent();
            if (soft && stored && !TestClasses.isInside(creation)) {
                violations.add(violation(sourceFile, creation,
                        "new ConcurrentReferenceHashMap<>() хранит записи по мягким ссылкам" + UNPREDICTABLE + ADVICE)
                        .withLevel(ErrorLevel.MINOR));
            }
        }
        violations.sort(Comparator.comparingInt(Violation::line));
        return violations;
    }

    // Строже всех находок - ссылка в роли ключа: такой кеш не работает совсем
    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.RESOURCE;
    }

    // Map<SoftReference<K>, V>
    private boolean hasSoftKey(Type type) {
        return typeArgument(type, 0).filter(this::isSoft).isPresent();
    }

    // Map<K, SoftReference<V>>
    private boolean hasSoftValue(Type type) {
        return typeArgument(type, 1).filter(this::isSoft).isPresent();
    }

    private boolean isSoft(Type type) {
        return SOFT_REFERENCE.equals(LocalTypes.typeName(type));
    }

    private Optional<Type> typeArgument(Type type, int index) {
        if (!type.isClassOrInterfaceType() || !MAP_TYPES.contains(LocalTypes.typeName(type))) {
            return Optional.empty();
        }
        ClassOrInterfaceType map = type.asClassOrInterfaceType();
        return map.getTypeArguments().filter(arguments -> arguments.size() > index).map(arguments -> arguments.get(index));
    }
}
