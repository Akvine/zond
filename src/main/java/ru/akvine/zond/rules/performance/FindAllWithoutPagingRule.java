package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.TestClasses;
import ru.akvine.zond.rules.support.Types;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class FindAllWithoutPagingRule extends AbstractRule {
    private static final String FIND_ALL = "findAll";
    private static final String FLUX = "Flux";
    private static final Pattern REACTIVE_REPOSITORY = Pattern.compile(
            "Reactive\\w*Repository|\\w*ReactiveRepository|R2dbcRepository|CoroutineCrudRepository");
    // Операторы, которые собирают весь поток в памяти
    private static final Set<String> COLLECTING = Set.of(
            "collectList", "collectSortedList", "collectMap", "collectMultimap", "block", "blockLast", "cache",
            "toIterable", "toStream");
    private static final Pattern DICTIONARY = Pattern.compile(
            ".*(type|status|role|authority|permission|country|currency|language|locale|setting|config|configuration"
                    + "|dictionary|category|kind|unit)s?(repository|repo|dao|mapper)", Pattern.CASE_INSENSITIVE);

    @Override
    public String code() {
        return RuleCodes.FIND_ALL_WITHOUT_PAGING_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет repository.findAll() без постраничной загрузки";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // findAll(pageable), findAll(specification) и т.п. не трогаем: там выборка ограничена
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> FIND_ALL.equals(call.getNameAsString()) && call.getArguments().isEmpty())
                .filter(Repositories::isRepositoryCall)
                .filter(call -> !TestClasses.isInside(call) && !isStreamed(call))
                .map(call -> report(sourceFile, call))
                .toList();
    }

    private Violation report(SourceFile sourceFile, MethodCallExpr call) {
        String text = "'" + call + "' загружает таблицу целиком: с ростом данных это долгий запрос и"
                + " OutOfMemoryError; используйте findAll(Pageable), запрос с условием или потоковую выборку";
        // roleRepository.findAll(), cardTypeRepository.findAll(): справочник из нескольких строк читают целиком
        // нарочно, обычно чтобы положить в кеш
        boolean isDictionary = call.getScope()
                .map(MethodCalls::receiverName)
                .filter(receiver -> DICTIONARY.matcher(receiver).matches())
                .isPresent();
        return isDictionary
                ? violation(sourceFile, call, text + ". Судя по имени, это справочник, который растет медленно,"
                + " поэтому это подозрение").withConfidence(Confidence.SUSPICION)
                : violation(sourceFile, call, text);
    }

    /**
     * Реактивный репозиторий отдает Flux: строки идут потоком по мере чтения и целиком в память не попадают -
     * пока поток не собрали в список (collectList) и не дождались его целиком (block)
     */
    private boolean isStreamed(MethodCallExpr call) {
        MethodCallExpr outermost = call;
        while (outermost.getParentNode().orElse(null) instanceof MethodCallExpr outer
                && outer.getScope().map(Nodes::unwrap).orElse(null) == outermost) {
            if (COLLECTING.contains(outer.getNameAsString())) {
                return false;
            }
            outermost = outer;
        }
        boolean isReactiveRepository = call.getScope()
                .map(Types::hierarchy)
                .filter(names -> names.stream().anyMatch(name -> REACTIVE_REPOSITORY.matcher(name).matches()))
                .isPresent();
        if (isReactiveRepository) {
            return true;
        }
        Node parent = outermost.getParentNode().orElse(null);
        if (parent instanceof ReturnStmt) {
            return Nodes.enclosingCallable(outermost)
                    .filter(callable -> callable instanceof MethodDeclaration method
                            && FLUX.equals(LocalTypes.typeName(method.getType())))
                    .isPresent();
        }
        return parent instanceof VariableDeclarator variable && FLUX.equals(LocalTypes.typeName(variable.getType()));
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.PERFORMANCE;
    }
}
