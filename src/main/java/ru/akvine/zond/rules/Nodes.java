package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import lombok.experimental.UtilityClass;

import java.util.Optional;

@UtilityClass
class Nodes {

    // ((x)) -> x
    Expression unwrap(Expression expression) {
        Expression current = expression;
        while (current.isEnclosedExpr()) {
            current = current.asEnclosedExpr().getInner();
        }
        return current;
    }

    /**
     * @return текст узла без комментариев - для сравнения двух фрагментов кода
     */
    String text(Node node) {
        Node copy = node.clone();
        copy.getAllContainedComments().forEach(Comment::remove);
        copy.removeComment();
        return copy.toString();
    }

    /**
     * @return метод, конструктор или блок инициализации, в котором находится узел
     */
    Optional<Node> enclosingCallable(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof MethodDeclaration
                    || current instanceof ConstructorDeclaration
                    || current instanceof InitializerDeclaration) {
                return Optional.of(current);
            }
            current = current.getParentNode().orElse(null);
        }
        return Optional.empty();
    }

    /**
     * @return true, если между узлом и границей есть лямбда, анонимный или локальный класс:
     * return / throw внутри них относятся уже к другой функции
     */
    boolean isInNestedScope(Node node, Node boundary) {
        Node current = node.getParentNode().orElse(null);
        while (current != null && current != boundary) {
            if (current instanceof LambdaExpr || current instanceof BodyDeclaration<?>) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    /**
     * @return ближайший цикл, в теле которого находится узел, в пределах текущего метода
     */
    Optional<Node> enclosingLoop(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null && !(current instanceof BodyDeclaration<?>) && !(current instanceof LambdaExpr)) {
            if (current instanceof ForStmt
                    || current instanceof ForEachStmt
                    || current instanceof WhileStmt
                    || current instanceof DoStmt) {
                return Optional.of(current);
            }
            current = current.getParentNode().orElse(null);
        }
        return Optional.empty();
    }
}
