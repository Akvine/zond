package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Loops;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.StreamChains;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Постраничная выдача через смещение: OFFSET в запросе, PageRequest.of(page, size), Pageable в обработчике.
 * Чтобы отдать страницу, база читает и отбрасывает все строки до нее, а если между запросами данные меняются,
 * записи на границах страниц дублируются или пропадают. Правило только советует keyset-пагинацию: для небольших
 * таблиц смещение допустимо, поэтому уровень - INFO. Обход всей таблицы в цикле ловит отдельное правило.
 */
@Component
public class OffsetPaginationRule extends AbstractRule {
    private static final String PAGE_REQUEST = "PageRequest";
    private static final Set<String> PAGE_FACTORIES = Set.of("of", "ofSize");
    private static final String WITH_PAGE = "withPage";
    // query.setFirstResult(offset)
    private static final String SET_FIRST_RESULT = "setFirstResult";
    // jOOQ / QueryDSL: .limit(size).offset(offset). Одного имени offset мало - так называют и позицию в буфере
    private static final String OFFSET = "offset";
    private static final Set<String> LIMITS = Set.of("limit", "fetchFirst", "maxResults");
    private static final String FIRST_PAGE = "0";
    // ... LIMIT ? OFFSET ?, LIMIT :size OFFSET :offset, MySQL LIMIT ?, ?
    private static final Pattern SQL_OFFSET = Pattern.compile("(?i)\\boffset\\s+[?:]|\\blimit\\s+[?:]\\w*\\s*,");

    private static final Set<String> CONTROLLERS = Set.of("RestController", "Controller");
    private static final Set<String> MAPPINGS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping");
    private static final Set<String> PAGEABLE_TYPES = Set.of("Pageable", "PageRequest");

    @Override
    public String code() {
        return RuleCodes.OFFSET_PAGINATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет постраничную выдачу через смещение (OFFSET, PageRequest, Pageable в"
                + " обработчике запроса) и советует keyset-пагинацию";
    }

    // Смещение видно точно, но мешает оно только на большой таблице и на дальних страницах - а этого по коду не узнать
    @Override
    public Confidence confidence() {
        return Confidence.SUSPICION;
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        Set<Integer> reportedLines = new HashSet<>();
        for (Node node : sourceFile.unit().findAll(Node.class)) {
            Optional<String> paging = describe(node);
            // В цикле смещение растет на каждом шаге - о таком обходе всей таблицы сообщает отдельное правило
            if (paging.isEmpty() || Loops.isRepeated(node) || TestClasses.isInside(node)) {
                continue;
            }
            if (reportedLines.add(node.getBegin().map(position -> position.line).orElse(0))) {
                violations.add(violation(sourceFile, node,
                        "Постраничная выдача через смещение (" + paging.get() + "): чтобы отдать страницу, база"
                                + " читает и отбрасывает все строки до нее - чем дальше страница, тем дороже запрос,"
                                + " а если между запросами строки добавляют или удаляют, записи на границах страниц"
                                + " дублируются или пропадают. Для лент, выгрузок и больших таблиц надежнее"
                                + " keyset-пагинация: WHERE id > :lastId ORDER BY id LIMIT :size (в Spring Data -"
                                + " ScrollPosition.keyset() и Window); для небольших справочников смещение допустимо"));
            }
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.INFO;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }

    private Optional<String> describe(Node node) {
        if (node instanceof MethodCallExpr call && !call.getArguments().isEmpty()) {
            String name = call.getNameAsString();
            boolean pageRequest = PAGE_FACTORIES.contains(name)
                    && call.getScope().filter(scope -> scope.toString().equals(PAGE_REQUEST)).isPresent()
                    // PageRequest.ofSize(size) - всегда первая страница
                    && call.getArguments().size() > 1
                    || WITH_PAGE.equals(name);
            boolean offset = call.getArguments().size() == 1 && call.getScope().isPresent()
                    && (SET_FIRST_RESULT.equals(name) || OFFSET.equals(name) && isLimited(call));
            return (pageRequest || offset) && !isFirstPage(call.getArgument(0))
                    ? Optional.of(Nodes.text(call))
                    : Optional.empty();
        }
        if (node instanceof ObjectCreationExpr creation && PAGE_REQUEST.equals(creation.getType().getNameAsString())
                && !creation.getArguments().isEmpty() && !isFirstPage(creation.getArgument(0))) {
            return Optional.of(Nodes.text(creation));
        }
        if (node instanceof StringLiteralExpr literal && SQL_OFFSET.matcher(literal.getValue()).find()
                || node instanceof TextBlockLiteralExpr block && SQL_OFFSET.matcher(block.getValue()).find()) {
            return Optional.of("OFFSET в запросе");
        }
        // Номер страницы задает клиент: page=100000 заставит базу прочитать и отбросить все предыдущие строки
        if (node instanceof Parameter parameter && PAGEABLE_TYPES.contains(LocalTypes.typeName(parameter.getType()))
                && isHandlerParameter(parameter)) {
            return Optional.of("параметр '" + parameter.getNameAsString() + "' обработчика запроса: номер страницы"
                    + " задает клиент");
        }
        return Optional.empty();
    }

    private boolean isLimited(MethodCallExpr offset) {
        List<MethodCallExpr> chain = new ArrayList<>(StreamChains.callsBefore(offset));
        chain.addAll(StreamChains.callsAfter(offset));
        return chain.stream().anyMatch(step -> LIMITS.contains(step.getNameAsString()));
    }

    // PageRequest.of(0, 20) - первые 20 строк: смещения нет
    private boolean isFirstPage(Expression page) {
        return FIRST_PAGE.equals(Nodes.unwrap(page).toString());
    }

    private boolean isHandlerParameter(Parameter parameter) {
        Optional<MethodDeclaration> method = parameter.getParentNode()
                .filter(parent -> parent instanceof MethodDeclaration)
                .map(parent -> (MethodDeclaration) parent);
        return method.filter(handler -> Annotations.hasAny(handler, MAPPINGS)).isPresent()
                && method.flatMap(handler -> handler.findAncestor(ClassOrInterfaceDeclaration.class))
                .filter(type -> Annotations.hasAny(type, CONTROLLERS))
                .isPresent();
    }
}
