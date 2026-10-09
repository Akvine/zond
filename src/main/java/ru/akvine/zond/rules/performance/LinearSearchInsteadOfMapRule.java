package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.KeyLookups;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loops;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Component
public class LinearSearchInsteadOfMapRule extends AbstractRule {
    @Override
    public String code() {
        return RuleCodes.LINEAR_SEARCH_INSTEAD_OF_MAP_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет поиск элемента по ключу перебором списка там, где быстрый доступ дала бы Map";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<KeyLookups.Lookup> lookups = Stream.concat(
                        sourceFile.unit().findAll(MethodCallExpr.class).stream().map(KeyLookups::ofStream),
                        sourceFile.unit().findAll(ForEachStmt.class).stream().map(KeyLookups::ofLoop))
                .flatMap(Optional::stream)
                // В тестах данных мало: перебор там ничего не стоит
                .filter(lookup -> !TestClasses.isInside(lookup.place()))
                .toList();

        List<Violation> violations = new ArrayList<>();
        for (KeyLookups.Lookup lookup : lookups) {
            String where = "'" + lookup.collection() + "' по '" + lookup.match().property() + "'";
            if (KeyLookups.isRepeated(lookup)) {
                violations.add(violation(sourceFile, lookup.place(),
                        "Поиск в " + where + " перебором на каждой итерации: коллекция всякий раз просматривается"
                                + " заново, вместе с циклом выходит O(N×M). Соберите перед циклом Map по этому"
                                + " ключу (Collectors.toMap, для нескольких значений - groupingBy) и берите элемент"
                                + " через get(" + lookup.match().key() + ")"));
            } else if (isLookupMethod(lookup)) {
                // Насколько это медленно, зависит от размера коллекции и частоты вызовов - по коду их не видно
                violations.add(violation(sourceFile, lookup.place(),
                        "Метод ищет элемент в " + where + " перебором: каждый вызов просматривает коллекцию"
                                + " целиком. Если она велика или метод вызывается часто, храните рядом Map по"
                                + " этому ключу - поиск перестанет зависеть от числа элементов")
                        .withConfidence(Confidence.SUSPICION));
            }
        }
        violations.sort(Comparator.comparingInt(Violation::line));
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

    // Метод вида findById(id): ищет в поле класса один элемент по значению своего параметра
    private boolean isLookupMethod(KeyLookups.Lookup lookup) {
        Optional<Node> declaration = LocalTypes.findDeclaration(lookup.collection());
        boolean inField = declaration
                .filter(found -> found instanceof VariableDeclarator)
                .flatMap(Node::getParentNode)
                .filter(parent -> parent instanceof FieldDeclaration)
                .isPresent();
        Optional<Node> method = Nodes.enclosingCallable(lookup.place()).filter(callable -> callable instanceof MethodDeclaration);
        if (!inField || method.isEmpty() || !lookup.findsOne() || KeyLookups.isFixed(declaration.get())
                || Loops.isRepeated(lookup.place())) {
            return false;
        }
        return lookup.match().key().findAll(NameExpr.class).stream()
                .anyMatch(name -> LocalTypes.findDeclaration(name)
                        .filter(found -> found instanceof Parameter)
                        .flatMap(Node::getParentNode)
                        .filter(method.get()::equals)
                        .isPresent());
    }
}
