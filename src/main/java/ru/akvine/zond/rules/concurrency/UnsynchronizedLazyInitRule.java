package ru.akvine.zond.rules.concurrency;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.SpringBeans;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class UnsynchronizedLazyInitRule extends AbstractRule {
    private static final String POST_CONSTRUCT = "PostConstruct";

    @Override
    public String code() {
        return RuleCodes.UNSYNCHRONIZED_LAZY_INIT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет ленивую инициализацию разделяемого поля без синхронизации";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (IfStmt ifStmt : sourceFile.unit().findAll(IfStmt.class)) {
            Optional<String> checked = nullCheckedName(ifStmt.getCondition());
            if (checked.isEmpty() || !assigns(ifStmt, checked.get()) || isSynchronized(ifStmt)) {
                continue;
            }

            // Поле делят несколько потоков, если оно статическое либо принадлежит Spring-бину
            Optional<FieldDeclaration> field = LocalTypes.findField(ifStmt, checked.get())
                    .flatMap(VariableDeclarator::getParentNode)
                    .filter(parent -> parent instanceof FieldDeclaration)
                    .map(parent -> (FieldDeclaration) parent);
            boolean isShared = field.filter(declaration -> declaration.isStatic() || isInsideBean(declaration)).isPresent();

            if (isShared && !field.get().isVolatile() && !isLocalVariable(ifStmt, checked.get())) {
                violations.add(violation(sourceFile, ifStmt,
                        "Ленивая инициализация поля '" + checked.get() + "' без синхронизации: два потока"
                                + " одновременно увидят null и создадут два объекта, а третий может получить"
                                + " недостроенный; инициализируйте поле при создании либо под синхронизацией"));
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

    // x == null, this.x == null, null == x
    private Optional<String> nullCheckedName(Expression condition) {
        Expression value = Nodes.unwrap(condition);
        if (!value.isBinaryExpr() || value.asBinaryExpr().getOperator() != BinaryExpr.Operator.EQUALS) {
            return Optional.empty();
        }
        Expression left = Nodes.unwrap(value.asBinaryExpr().getLeft());
        Expression right = Nodes.unwrap(value.asBinaryExpr().getRight());
        if (left.isNullLiteralExpr()) {
            return name(right);
        }
        return right.isNullLiteralExpr() ? name(left) : Optional.empty();
    }

    private Optional<String> name(Expression expression) {
        if (expression.isNameExpr()) {
            return Optional.of(expression.asNameExpr().getNameAsString());
        }
        if (expression.isFieldAccessExpr() && expression.asFieldAccessExpr().getScope().isThisExpr()) {
            return Optional.of(expression.asFieldAccessExpr().getNameAsString());
        }
        return Optional.empty();
    }

    private boolean assigns(IfStmt ifStmt, String field) {
        return ifStmt.getThenStmt().findAll(AssignExpr.class).stream()
                .anyMatch(assign -> name(assign.getTarget()).filter(field::equals).isPresent());
    }

    // Локальная переменная с тем же именем перекрывает поле
    private boolean isLocalVariable(IfStmt ifStmt, String name) {
        return LocalTypes.findDeclaration(ifStmt, name)
                .filter(declaration -> declaration.getParentNode()
                        .filter(parent -> parent instanceof FieldDeclaration)
                        .isEmpty())
                .isPresent();
    }

    // synchronized-метод или блок; конструктор и @PostConstruct выполняются до того, как объект увидят другие потоки.
    // Вариант с проверкой внутри synchronized (double-checked locking) разбирает отдельное правило
    private boolean isSynchronized(IfStmt ifStmt) {
        if (!ifStmt.findAll(SynchronizedStmt.class).isEmpty()) {
            return true;
        }
        Node current = ifStmt.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof SynchronizedStmt) {
                return true;
            }
            if (current instanceof MethodDeclaration method) {
                return method.isSynchronized() || Annotations.has(method, POST_CONSTRUCT);
            }
            current = current.getParentNode().orElse(null);
        }
        // Вне метода: конструктор или блок инициализации
        return true;
    }

    private boolean isInsideBean(Node node) {
        return node.getParentNode()
                .filter(parent -> parent instanceof ClassOrInterfaceDeclaration type && SpringBeans.isBean(type))
                .isPresent();
    }
}
