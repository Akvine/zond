package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithArguments;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.BeanScopes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Устаревшие синхронизированные коллекции. У Hashtable, Vector и Stack каждый метод synchronized: блокировка
 * берется на каждый вызов даже там, где коллекцию видит один поток, а составные действия (проверить и добавить,
 * обойти) атомарными все равно не становятся. Hashtable вдобавок не принимает null. Обертка
 * Collections.synchronized... в общем поле дает то же самое: одна блокировка на всю коллекцию, обход и составные
 * действия нужно отдельно заключать в synchronized - а в java.util.concurrent есть коллекции, которые делают
 * это сами.
 */
@Component
public class LegacySynchronizedCollectionRule extends AbstractRule {
    private static final String HASHTABLE = "Hashtable";
    private static final String VECTOR = "Vector";
    private static final String STACK = "Stack";
    private static final Map<String, String> LEGACY_ADVICE = Map.of(
            HASHTABLE, "каждый метод synchronized - блокировка на всю таблицу при каждом вызове, а null в ключе"
                    + " или значении дает NullPointerException; используйте HashMap, а для доступа из нескольких"
                    + " потоков - ConcurrentHashMap",
            VECTOR, "каждый метод synchronized, а составные действия (проверить и добавить, обойти) атомарными"
                    + " все равно не становятся; используйте ArrayList, а для доступа из нескольких потоков -"
                    + " CopyOnWriteArrayList либо явную блокировку",
            STACK, "наследник Vector: каждый метод synchronized, а обход идет снизу вверх, не в порядке pop();"
                    + " используйте ArrayDeque (push, pop, peek)");

    private static final String COLLECTIONS = "Collections";
    private static final Pattern SYNCHRONIZED_WRAPPER = Pattern.compile("synchronized(List|Map|Set|Collection|"
            + "SortedMap|SortedSet|NavigableMap|NavigableSet)");
    // Что обернуто - и чем это заменить. LinkedHashMap (порядок, LRU), WeakHashMap, IdentityHashMap
    // конкурентной замены в JDK не имеют, и обертка для них - честный выбор
    private static final Map<String, String> CONCURRENT_REPLACEMENTS = Map.of(
            "ArrayList", "CopyOnWriteArrayList, если список чаще читают, чем меняют, либо ConcurrentLinkedQueue",
            "LinkedList", "ConcurrentLinkedDeque либо LinkedBlockingDeque",
            "HashMap", "ConcurrentHashMap (computeIfAbsent, merge делают составные действия атомарно)",
            "HashSet", "ConcurrentHashMap.newKeySet()",
            "TreeMap", "ConcurrentSkipListMap",
            "TreeSet", "ConcurrentSkipListSet");

    // Старые API, которые сами требуют Hashtable или Vector: окружение JNDI, модели Swing
    private static final Pattern LEGACY_API = Pattern.compile(
            "Initial\\w*Context|\\w*LdapContext|DefaultTableModel|DefaultComboBoxModel|JComboBox|JList|JTable|JTree");
    private static final String CONTEXT_CONSTANTS = "Context.";

    @Override
    public String code() {
        return RuleCodes.LEGACY_SYNCHRONIZED_COLLECTION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет устаревшие синхронизированные коллекции: Hashtable, Vector, Stack и обертки"
                + " Collections.synchronized... в общих полях";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (ObjectCreationExpr creation : sourceFile.unit().findAll(ObjectCreationExpr.class)) {
            String type = creation.getType().getNameAsString();
            if (LEGACY_ADVICE.containsKey(type) && !TestClasses.isInside(creation) && !feedsLegacyApi(creation)) {
                violations.add(violation(sourceFile, creation,
                        "'new " + type + "': " + LEGACY_ADVICE.get(type)));
            }
        }
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!isSynchronizedWrapper(call) || TestClasses.isInside(call)) {
                continue;
            }
            Optional<String> replacement = wrappedType(call).map(CONCURRENT_REPLACEMENTS::get);
            Optional<String> field = sharedField(call);
            if (replacement.isPresent() && field.isPresent()) {
                violations.add(violation(sourceFile, call,
                        "Общее поле '" + field.get() + "' обернуто в Collections." + call.getNameAsString() + ": одна"
                                + " блокировка на всю коллекцию при каждом вызове, а обход и составные действия"
                                + " (if (!contains(x)) add(x)) все равно нужно заключать в synchronized ("
                                + field.get() + ") - иначе гонка или ConcurrentModificationException; используйте "
                                + replacement.get()));
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
        return ErrorType.CODE_SMELL;
    }

    private boolean isSynchronizedWrapper(MethodCallExpr call) {
        return SYNCHRONIZED_WRAPPER.matcher(call.getNameAsString()).matches() && call.getArguments().size() == 1
                && call.getScope().filter(scope -> scope.toString().equals(COLLECTIONS)
                || scope.toString().endsWith("." + COLLECTIONS)).isPresent();
    }

    // Collections.synchronizedList(new ArrayList<>()) -> ArrayList; анонимный класс (LRU на removeEldestEntry) - нет
    private Optional<String> wrappedType(MethodCallExpr call) {
        Expression argument = Nodes.unwrap(call.getArgument(0));
        if (argument.isObjectCreationExpr() && argument.asObjectCreationExpr().getAnonymousClassBody().isEmpty()) {
            return Optional.of(argument.asObjectCreationExpr().getType().getNameAsString());
        }
        return Optional.empty();
    }

    // Статическое поле любого класса либо поле singleton-бина: его видят все потоки
    private Optional<String> sharedField(MethodCallExpr call) {
        Node parent = call.getParentNode().orElse(null);
        Optional<VariableDeclarator> field = Optional.empty();
        if (parent instanceof VariableDeclarator variable && variable.getParentNode().orElse(null) instanceof FieldDeclaration) {
            field = Optional.of(variable);
        } else if (parent instanceof AssignExpr assign && assign.getValue() == call) {
            Expression target = assign.getTarget();
            String name = target.isFieldAccessExpr() && target.asFieldAccessExpr().getScope().isThisExpr()
                    ? target.asFieldAccessExpr().getNameAsString()
                    : target.isNameExpr() ? target.asNameExpr().getNameAsString() : null;
            if (name != null) {
                field = LocalTypes.findField(call, name);
            }
        }
        return field.filter(this::isShared).map(VariableDeclarator::getNameAsString);
    }

    private boolean isShared(VariableDeclarator variable) {
        Optional<FieldDeclaration> declaration = variable.getParentNode()
                .filter(parent -> parent instanceof FieldDeclaration)
                .map(parent -> (FieldDeclaration) parent);
        if (declaration.isEmpty()) {
            return false;
        }
        return declaration.get().isStatic() || declaration.get().findAncestor(ClassOrInterfaceDeclaration.class)
                .filter(BeanScopes::isSingletonBean)
                .isPresent();
    }

    // new InitialDirContext(env), new DefaultTableModel(rows, columns), env.put(Context.PROVIDER_URL, url):
    // API само требует Hashtable или Vector
    private boolean feedsLegacyApi(ObjectCreationExpr creation) {
        if (isLegacyApiArgument(creation)) {
            return true;
        }
        Node parent = creation.getParentNode().orElse(null);
        if (!(parent instanceof VariableDeclarator variable)) {
            return false;
        }
        String name = variable.getNameAsString();
        Optional<Node> scope = creation.findAncestor(CallableDeclaration.class).map(callable -> (Node) callable);
        return scope.stream()
                .flatMap(callable -> callable.findAll(NameExpr.class).stream())
                .filter(usage -> usage.getNameAsString().equals(name))
                .anyMatch(usage -> isLegacyApiArgument(usage) || isFilledWithContextConstants(usage));
    }

    private boolean isLegacyApiArgument(Expression value) {
        Node parent = value.getParentNode().orElse(null);
        if (parent instanceof ObjectCreationExpr owner && isArgument(owner, value)) {
            return LEGACY_API.matcher(owner.getType().getNameAsString()).matches();
        }
        return parent instanceof MethodCallExpr call && isArgument(call, value)
                && call.getScope().filter(scope -> LEGACY_API.matcher(LocalTypes.typeOf(scope).orElse("")).matches())
                .isPresent();
    }

    private boolean isArgument(NodeWithArguments<?> owner, Expression value) {
        return owner.getArguments().stream().anyMatch(argument -> argument == value);
    }

    // env.put(Context.INITIAL_CONTEXT_FACTORY, ...): окружение JNDI
    private boolean isFilledWithContextConstants(NameExpr usage) {
        return usage.getParentNode()
                .filter(parent -> parent instanceof MethodCallExpr call && call.getScope().filter(scope -> scope == usage).isPresent()
                        && call.getArguments().stream().anyMatch(argument -> argument.toString().startsWith(CONTEXT_CONSTANTS)))
                .isPresent();
    }
}
