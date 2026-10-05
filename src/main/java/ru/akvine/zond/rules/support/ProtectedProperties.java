package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import ru.akvine.zond.models.SourceFile;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Какие "секретные" свойства на деле хранят защищенное значение. Поле password у сущности секретом
 * не является, если в него везде кладут результат passwordEncoder.encode(...): в логе окажется хеш.
 * Чтобы это узнать, по всему проекту собираются места, где свойству присваивают значение.
 */
public final class ProtectedProperties {
    private static final String GETTER_PREFIX = "get";

    private static WeakReference<List<SourceFile>> cachedSources = new WeakReference<>(null);
    private static ProtectedProperties cached;

    private enum Assigned { PROTECTED, PLAIN, UNKNOWN }

    /**
     * Сколько раз свойству присвоили защищенное значение и сколько - открытое
     */
    private static final class Usage {
        private int protectedCount;
        private int plainCount;
    }

    // "Тип.свойство" либо просто "свойство", если тип получателя неизвестен
    private final Map<String, Usage> usages = new HashMap<>();

    public static synchronized ProtectedProperties of(List<SourceFile> sources) {
        if (cachedSources.get() != sources) {
            cached = new ProtectedProperties(sources);
            cachedSources = new WeakReference<>(sources);
        }
        return cached;
    }

    private ProtectedProperties(List<SourceFile> sources) {
        for (SourceFile source : sources) {
            for (MethodCallExpr call : source.unit().findAll(MethodCallExpr.class)) {
                // user.setPassword(...), User.builder().password(...)
                if (call.getArguments().size() == 1 && !TestClasses.isInside(call)) {
                    String property = PropertyAccess.writtenProperty(call.getNameAsString());
                    if (Secrets.isSecretName(property)) {
                        record(call.getScope().flatMap(PropertyAccess::ownerType), property, classify(call.getArgument(0), property));
                    }
                }
            }
            for (AssignExpr assignment : source.unit().findAll(AssignExpr.class)) {
                // this.password = ... в конструкторе или методе
                String property = MethodCalls.receiverName(assignment.getTarget());
                boolean isOwnField = assignment.getTarget().isNameExpr() || assignment.getTarget().isFieldAccessExpr()
                        && assignment.getTarget().asFieldAccessExpr().getScope().isThisExpr();
                if (isOwnField && Secrets.isSecretName(property) && !TestClasses.isInside(assignment)) {
                    Optional<String> type = assignment.findAncestor(TypeDeclaration.class)
                            .map(found -> ((TypeDeclaration<?>) found).getNameAsString());
                    record(type, property, classify(assignment.getValue(), property));
                }
            }
        }
    }

    /**
     * @param type     тип объекта, у которого читают свойство; пусто, если он неизвестен
     * @param property имя свойства: password для getPassword()
     * @return true, если свойству присваивают только защищенные значения (и хотя бы одно такое место найдено)
     */
    public boolean isProtected(Optional<String> type, String property) {
        String name = PropertyAccess.decapitalize(property);
        // Тип известен - смотрим только на него: у сущности в password лежит хеш, у запроса - сам пароль
        Usage usage = type.isPresent() ? usages.get(type.get() + "." + name) : usages.get(name);
        return usage != null && usage.protectedCount > 0 && usage.plainCount == 0;
    }

    /**
     * @return true, если выражение дает защищенное значение: вызов шифрования или хеширования, имя вида
     * encryptedPassword, локальная переменная, заданная таким вызовом, либо геттер защищенного свойства
     */
    public boolean isProtectedValue(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (SecretExposure.isProtectingCall(value)) {
            return true;
        }
        if (value.isNameExpr()) {
            return LocalTypes.findInitializer(value)
                    .filter(initializer -> initializer != value && isProtectedValue(initializer))
                    .isPresent();
        }
        if (value.isMethodCallExpr() && value.asMethodCallExpr().getArguments().isEmpty()) {
            MethodCallExpr getter = value.asMethodCallExpr();
            String name = getter.getNameAsString();
            String property = name.startsWith(GETTER_PREFIX) && name.length() > GETTER_PREFIX.length()
                    ? name.substring(GETTER_PREFIX.length())
                    : name;
            return isProtected(getter.getScope().flatMap(LocalTypes::typeOf), property);
        }
        if (value.isFieldAccessExpr()) {
            return isProtected(LocalTypes.typeOf(value.asFieldAccessExpr().getScope()),
                    value.asFieldAccessExpr().getNameAsString());
        }
        return false;
    }

    private void record(Optional<String> type, String property, Assigned assigned) {
        if (assigned == Assigned.UNKNOWN) {
            return;
        }
        String name = PropertyAccess.decapitalize(property);
        for (String key : type.map(owner -> List.of(owner + "." + name, name)).orElse(List.of(name))) {
            Usage usage = usages.computeIfAbsent(key, created -> new Usage());
            if (assigned == Assigned.PROTECTED) {
                usage.protectedCount++;
            } else {
                usage.plainCount++;
            }
        }
    }

    private Assigned classify(Expression argument, String property) {
        Expression value = Nodes.unwrap(argument);
        if (value.isNullLiteralExpr()) {
            return Assigned.UNKNOWN;
        }
        if (SecretExposure.isProtectingCall(value)) {
            return Assigned.PROTECTED;
        }
        String name = MethodCalls.receiverName(value);
        if (Secrets.isProtectedName(name)) {
            return Assigned.PROTECTED;
        }
        if (value.isNameExpr()) {
            return classifyVariable(value.asNameExpr(), property);
        }
        // Копирование из другого объекта (entity.setPassword(dto.getPassword())): что там лежит, неизвестно
        boolean isCopy = value.isMethodCallExpr() && value.asMethodCallExpr().getArguments().isEmpty()
                || value.isFieldAccessExpr();
        return isCopy ? Assigned.UNKNOWN : Assigned.PLAIN;
    }

    private Assigned classifyVariable(NameExpr variable, String property) {
        Optional<Expression> initializer = LocalTypes.findInitializer(variable);
        if (initializer.isPresent() && initializer.get() != variable) {
            return classify(initializer.get(), property);
        }
        // Параметр сеттера или конструктора (this.password = password): значение приходит от вызывающего
        boolean isParameter = LocalTypes.findDeclaration(variable).filter(Parameter.class::isInstance).isPresent();
        return isParameter ? Assigned.UNKNOWN : Assigned.PLAIN;
    }
}
