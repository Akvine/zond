package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class ListRemoveByIndexRule extends AbstractRule {
    private static final String REMOVE = "remove";
    private static final Set<String> LIST_TYPES = Set.of("List", "ArrayList", "LinkedList", "CopyOnWriteArrayList", "Vector");
    private static final String INTEGER = "Integer";
    private static final Set<String> INT_TYPES = Set.of("int", "short", "byte", "char");

    @Override
    public String code() {
        return RuleCodes.LIST_REMOVE_BY_INDEX_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет remove(int) у списка чисел: удаляется элемент по индексу, а не по значению";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!REMOVE.equals(call.getNameAsString()) || call.getArguments().size() != 1 || call.getScope().isEmpty()
                    || !isListOfIntegers(call.getScope().get())) {
                continue;
            }
            Expression argument = Nodes.unwrap(call.getArgument(0));
            if (isPrimitiveInt(argument) && !isLoopCounter(argument)) {
                violations.add(violation(sourceFile, call,
                        "У списка чисел вызван remove(" + argument + ") с аргументом типа int: удалится элемент"
                                + " с таким индексом, а не такое значение. Если нужно удалить значение - передайте"
                                + " Integer.valueOf(" + argument + "); если индекс - это стоит пояснить"
                                + " именем переменной"));
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
        return ErrorType.LOGICAL;
    }

    // List<Integer> ids
    private boolean isListOfIntegers(Expression scope) {
        Optional<Type> type = LocalTypes.findDeclaration(scope).map(this::typeOf);
        return type.filter(Type::isClassOrInterfaceType)
                .map(Type::asClassOrInterfaceType)
                .filter(declared -> LIST_TYPES.contains(declared.getNameAsString()))
                .flatMap(declared -> declared.getTypeArguments())
                .filter(arguments -> arguments.size() == 1 && INTEGER.equals(arguments.get(0).asString()))
                .isPresent();
    }

    private Type typeOf(Node declaration) {
        if (declaration instanceof Parameter parameter) {
            return parameter.getType();
        }
        return declaration instanceof VariableDeclarator variable ? variable.getType() : null;
    }

    private boolean isPrimitiveInt(Expression argument) {
        if (argument.isIntegerLiteralExpr()) {
            return true;
        }
        return argument.isNameExpr() && LocalTypes.findDeclaration(argument)
                .flatMap(LocalTypes::declaredType)
                .filter(INT_TYPES::contains)
                .isPresent();
    }

    // for (int i = ...; ...) list.remove(i) - счетчик цикла заведомо индекс
    private boolean isLoopCounter(Expression argument) {
        if (!argument.isNameExpr()) {
            return false;
        }
        String name = argument.asNameExpr().getNameAsString();
        return argument.findAncestor(ForStmt.class, loop -> loop.getInitialization().stream()
                .filter(Expression::isVariableDeclarationExpr)
                .flatMap(declaration -> declaration.asVariableDeclarationExpr().getVariables().stream())
                .anyMatch(variable -> variable.getNameAsString().equals(name))).isPresent();
    }
}
