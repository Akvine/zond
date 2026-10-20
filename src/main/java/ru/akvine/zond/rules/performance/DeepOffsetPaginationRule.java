package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loops;
import ru.akvine.zond.rules.support.Nodes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Обход всей таблицы страницами через OFFSET: на каждой следующей странице база заново читает и отбрасывает
 * все строки до смещения, и обход N страниц стоит O(N²). Одна страница для API находкой не является - только
 * цикл, который двигает номер страницы или смещение.
 */
@Component
public class DeepOffsetPaginationRule extends AbstractRule {
    private static final String PAGE_REQUEST = "PageRequest";
    private static final Set<String> PAGE_FACTORIES = Set.of("of", "ofSize");
    private static final String WITH_PAGE = "withPage";
    // page.nextPageable(), pageable.next()
    private static final Set<String> NEXT_PAGEABLE = Set.of("nextPageable", "nextOrLastPageable");
    private static final String NEXT = "next";
    private static final Set<String> PAGEABLE_TYPES = Set.of("Pageable", "PageRequest");
    private static final Pattern PAGE_OBJECT = Pattern.compile(".*(page|pageable|pageRequest|slice)$", Pattern.CASE_INSENSITIVE);
    // query.setFirstResult(offset), jOOQ / QueryDSL .offset(offset)
    private static final Set<String> OFFSET_SETTERS = Set.of("setFirstResult", "offset");
    // ... LIMIT ? OFFSET ?, LIMIT :size OFFSET :offset, MySQL LIMIT ?, ?
    private static final Pattern SQL_OFFSET = Pattern.compile(
            "(?i)\\boffset\\s+[?:]|\\blimit\\s+[?:]\\w*\\s*,");

    @Override
    public String code() {
        return RuleCodes.DEEP_OFFSET_PAGINATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет обход всей таблицы постранично через OFFSET: цикл, который двигает номер"
                + " страницы или смещение";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        Set<Integer> reportedLines = new HashSet<>();
        for (Node node : sourceFile.unit().findAll(Node.class)) {
            Optional<String> paging = describePaging(node);
            if (paging.isPresent() && reportedLines.add(node.getBegin().map(position -> position.line).orElse(0))) {
                violations.add(violation(sourceFile, node,
                        "Постраничный обход в цикле через смещение (" + paging.get() + "): каждая следующая страница"
                                + " дороже предыдущей - база заново читает и отбрасывает все строки до смещения,"
                                + " и обход всей таблицы стоит O(N²); используйте keyset-пагинацию"
                                + " (WHERE id > :lastId ORDER BY id LIMIT :size) либо потоковое чтение"));
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
        return ErrorType.PERFORMANCE;
    }

    private Optional<String> describePaging(Node node) {
        // PageRequest.of(page, size), Pageable.ofSize(size).withPage(page), query.setFirstResult(offset)
        if (node instanceof MethodCallExpr call) {
            String name = call.getNameAsString();
            boolean pageRequest = PAGE_FACTORIES.contains(name)
                    && call.getScope().filter(scope -> scope.toString().equals(PAGE_REQUEST)).isPresent()
                    || WITH_PAGE.equals(name);
            if ((pageRequest || OFFSET_SETTERS.contains(name) && call.getArguments().size() == 1)
                    && !call.getArguments().isEmpty()) {
                return inIteration(call).filter(iteration -> isAdvancedIn(call.getArgument(0), iteration))
                        .map(iteration -> call.toString());
            }
            // page = repository.findAll(page.nextPageable())
            if (call.getArguments().isEmpty() && (NEXT_PAGEABLE.contains(name) || NEXT.equals(name) && call.getScope()
                    .filter(scope -> PAGE_OBJECT.matcher(scope.toString()).matches()
                            || LocalTypes.typeOf(scope).filter(PAGEABLE_TYPES::contains).isPresent()).isPresent())) {
                return inIteration(call).map(iteration -> call.toString());
            }
            return Optional.empty();
        }
        if (node instanceof ObjectCreationExpr creation && PAGE_REQUEST.equals(creation.getType().getNameAsString())
                && !creation.getArguments().isEmpty()) {
            return inIteration(creation).filter(iteration -> isAdvancedIn(creation.getArgument(0), iteration))
                    .map(iteration -> creation.toString());
        }
        // Текст запроса со смещением, а смещение в цикле растет
        String text = node instanceof StringLiteralExpr literal ? literal.getValue()
                : node instanceof TextBlockLiteralExpr block ? block.getValue() : null;
        if (text != null && SQL_OFFSET.matcher(text).find()) {
            return inIteration(node).filter(this::advancesAnyCounter).map(iteration -> "OFFSET в запросе");
        }
        return Optional.empty();
    }

    private Optional<Node> inIteration(Node node) {
        return Loops.enclosingIteration(node);
    }

    // Номер страницы или смещение - переменная, которую цикл увеличивает: page++, offset += size, page = page + 1
    private boolean isAdvancedIn(Expression argument, Node iteration) {
        Expression value = Nodes.unwrap(argument);
        if (value.isUnaryExpr() && isIncrement(value.asUnaryExpr())) {
            return value.asUnaryExpr().getExpression().isNameExpr();
        }
        if (!value.isNameExpr()) {
            // page * size, offset + i
            return value.findAll(NameExpr.class).stream()
                    .anyMatch(name -> isAdvanced(name.getNameAsString(), iteration));
        }
        return isAdvanced(value.asNameExpr().getNameAsString(), iteration);
    }

    private boolean isAdvanced(String variable, Node iteration) {
        boolean incremented = iteration.findAll(UnaryExpr.class).stream()
                .filter(this::isIncrement)
                .anyMatch(unary -> unary.getExpression().toString().equals(variable));
        return incremented || iteration.findAll(AssignExpr.class).stream()
                .filter(assignment -> assignment.getTarget().toString().equals(variable))
                .anyMatch(assignment -> assignment.getOperator() == AssignExpr.Operator.PLUS
                        || assignment.getValue().findAll(NameExpr.class).stream()
                        .anyMatch(name -> name.getNameAsString().equals(variable)));
    }

    private boolean advancesAnyCounter(Node iteration) {
        return !iteration.findAll(UnaryExpr.class).stream().filter(this::isIncrement).toList().isEmpty()
                || iteration.findAll(AssignExpr.class).stream()
                .anyMatch(assignment -> assignment.getOperator() == AssignExpr.Operator.PLUS);
    }

    private boolean isIncrement(UnaryExpr unary) {
        return unary.getOperator() == UnaryExpr.Operator.POSTFIX_INCREMENT
                || unary.getOperator() == UnaryExpr.Operator.PREFIX_INCREMENT;
    }
}
