package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.RepositoryEntities;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Потерянное обновление: значение поля сущности читают, меняют и записывают обратно, а между чтением
 * и записью другой запрос делает то же самое. Одно из изменений пропадает.
 */
@Component
public class LostUpdateRule extends AbstractRule implements ProjectRule {
    private static final Pattern SETTER = Pattern.compile("^set[A-Z].*");
    private static final int PREFIX_LENGTH = 3;
    private static final Set<BinaryExpr.Operator> ARITHMETIC = Set.of(
            BinaryExpr.Operator.PLUS, BinaryExpr.Operator.MINUS, BinaryExpr.Operator.MULTIPLY,
            BinaryExpr.Operator.DIVIDE);
    // balance.add(amount), counter.plus(1): то же сложение, записанное вызовом
    private static final Set<String> ARITHMETIC_METHODS = Set.of(
            "add", "subtract", "multiply", "divide", "plus", "minus");

    private static final String ENTITY = "Entity";
    private static final String VERSION = "Version";
    private static final String LOCK = "Lock";
    private static final Set<String> ENTITY_MANAGER_READS = Set.of("find", "getReference");
    // Чтение с блокировкой: findByIdForUpdate, findWithLockById, lockById
    private static final Pattern LOCKING_NAME = Pattern.compile("(?i).*(forupdate|lock).*");
    private static final Pattern LOCKING_CODE = Pattern.compile(".*(LockModeType|PESSIMISTIC|tryLock\\(|\\.lock\\().*", Pattern.DOTALL);

    @Override
    public String code() {
        return RuleCodes.LOST_UPDATE_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет изменение поля сущности по схеме \"прочитал - изменил - записал\" без блокировки и без @Version";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        ProjectClasses classes = ProjectClasses.of(sourceFiles);
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodCallExpr setter : sourceFile.unit().findAll(MethodCallExpr.class)) {
                describe(setter, classes).ifPresent(place -> violations.add(violation(sourceFile, setter,
                        "Значение '" + place + "' читается, меняется и записывается обратно без блокировки"
                                + " и без @Version: два одновременных запроса прочитают одно и то же значение,"
                                + " и одно из изменений потеряется; добавьте в сущность @Version, читайте"
                                + " с блокировкой (@Lock(PESSIMISTIC_WRITE)) либо меняйте значение одним запросом"
                                + " UPDATE ... SET x = x + :n")));
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
        return ErrorType.CONCURRENCY;
    }

    /**
     * @return "order.total", если вызов - order.setTotal(order.getTotal() + ...) на сущности без защиты
     */
    private Optional<String> describe(MethodCallExpr setter, ProjectClasses classes) {
        if (!SETTER.matcher(setter.getNameAsString()).matches() || setter.getArguments().size() != 1
                || setter.getScope().filter(Expression::isNameExpr).isEmpty()) {
            return Optional.empty();
        }
        Expression owner = setter.getScope().get();
        String property = setter.getNameAsString().substring(PREFIX_LENGTH);
        if (!changesOwnValue(Nodes.unwrap(setter.getArgument(0)), owner.toString(), property)) {
            return Optional.empty();
        }

        Optional<ClassOrInterfaceDeclaration> entity = LocalTypes.typeOf(owner).flatMap(classes::find)
                .filter(type -> Annotations.has(type, ENTITY));
        if (entity.isEmpty() || isVersioned(entity.get(), classes) || !isReadWithoutLock(owner, classes)) {
            return Optional.empty();
        }
        return Optional.of(owner + "." + Character.toLowerCase(property.charAt(0)) + property.substring(1));
    }

    // x.getTotal() + n, x.getTotal().add(n); приклеивание строки к строке счетчиком не считается
    private boolean changesOwnValue(Expression value, String owner, String property) {
        if (value.isBinaryExpr()) {
            BinaryExpr binary = value.asBinaryExpr();
            boolean text = binary.getLeft().isStringLiteralExpr() || binary.getRight().isStringLiteralExpr();
            return ARITHMETIC.contains(binary.getOperator()) && !text
                    && (isGetter(binary.getLeft(), owner, property) || isGetter(binary.getRight(), owner, property));
        }
        if (value.isMethodCallExpr()) {
            MethodCallExpr call = value.asMethodCallExpr();
            return ARITHMETIC_METHODS.contains(call.getNameAsString())
                    && call.getScope().filter(scope -> isGetter(scope, owner, property)).isPresent();
        }
        return false;
    }

    private boolean isGetter(Expression expression, String owner, String property) {
        Expression value = Nodes.unwrap(expression);
        if (!value.isMethodCallExpr()) {
            return false;
        }
        MethodCallExpr call = value.asMethodCallExpr();
        return call.getNameAsString().equals("get" + property)
                && call.getArguments().isEmpty()
                && call.getScope().filter(scope -> scope.toString().equals(owner)).isPresent();
    }

    // Поле @Version может стоять в общем предке сущностей; о предке из библиотеки ничего не известно -
    // считаем, что версия там есть
    private boolean isVersioned(ClassOrInterfaceDeclaration entity, ProjectClasses classes) {
        Set<ClassOrInterfaceDeclaration> seen = new HashSet<>();
        Optional<ClassOrInterfaceDeclaration> current = Optional.of(entity);
        while (current.isPresent() && seen.add(current.get())) {
            boolean versioned = current.get().getFields().stream().anyMatch(field -> Annotations.has(field, VERSION))
                    || current.get().getMethods().stream().anyMatch(method -> Annotations.has(method, VERSION));
            if (versioned) {
                return true;
            }
            current = classes.parent(current.get());
        }
        return classes.hasUnknownAncestor(entity);
    }

    // Сущность прочитана из базы в этом же методе обычным запросом: параметр и поле приходят неизвестно откуда,
    // а чтение с блокировкой гонку исключает
    private boolean isReadWithoutLock(Expression owner, ProjectClasses classes) {
        Optional<Node> declaration = LocalTypes.findDeclaration(owner);
        if (declaration.isEmpty() || !(declaration.get() instanceof VariableDeclarator variable)
                || variable.getInitializer().isEmpty()) {
            return false;
        }
        Optional<MethodCallExpr> read = variable.getInitializer().get().findAll(MethodCallExpr.class).stream()
                .filter(call -> Repositories.isRepositoryCall(call) || isEntityManagerRead(call))
                .findFirst();
        if (read.isEmpty() || LOCKING_NAME.matcher(read.get().getNameAsString()).matches()
                || isLockedQuery(read.get(), classes)) {
            return false;
        }
        Optional<MethodDeclaration> method = owner.findAncestor(MethodDeclaration.class);
        return method.isPresent()
                && !method.get().isSynchronized()
                && owner.findAncestor(SynchronizedStmt.class).isEmpty()
                && !LOCKING_CODE.matcher(method.get().toString()).matches();
    }

    private boolean isEntityManagerRead(MethodCallExpr call) {
        return ENTITY_MANAGER_READS.contains(call.getNameAsString())
                && call.getScope().filter(scope -> scope.toString().toLowerCase().contains("entitymanager")).isPresent();
    }

    // @Lock на методе репозитория: запрос сам берет блокировку
    private boolean isLockedQuery(MethodCallExpr read, ProjectClasses classes) {
        return read.getScope()
                .flatMap(scope -> RepositoryEntities.repositoryOf(scope, classes))
                .map(repository -> repository.getMethodsByName(read.getNameAsString()))
                .filter(methods -> methods.stream().anyMatch(method -> Annotations.has(method, LOCK)))
                .isPresent();
    }
}
