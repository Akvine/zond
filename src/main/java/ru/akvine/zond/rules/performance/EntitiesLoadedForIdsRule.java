package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.StreamChains;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code qrCodeRepository.findByTeamId(teamId).stream().map(QrCode::getId).toList()}: база отдает строки целиком,
 * Hibernate собирает из них сущности и ставит их на учет, а нужна одна колонка. Запрос с проекцией
 * (select q.id from ...) отдаст сразу список идентификаторов.
 */
@Component
public class EntitiesLoadedForIdsRule extends AbstractRule {
    private static final String MAP = "map";
    private static final String STREAM = "stream";
    private static final String GET_ID = "getId";
    private static final Pattern READ = Pattern.compile("(find|get|read|query|search|load|fetch|select)\\w*");
    // Одна запись по ключу - не выборка
    private static final Pattern SINGLE = Pattern.compile("(find|get)(One)?ById|getReferenceById|findOne");

    @Override
    public String code() {
        return RuleCodes.ENTITIES_LOADED_FOR_IDS_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет выборки из репозитория, от которых берут только идентификаторы:"
                + " repository.findBy...().stream().map(Entity::getId)";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (MethodCallExpr map : sourceFile.unit().findAll(MethodCallExpr.class)) {
            if (!MAP.equals(map.getNameAsString()) || map.getArguments().size() != 1
                    || !takesId(map.getArgument(0)) || TestClasses.isInside(map)) {
                continue;
            }
            findQuery(map).ifPresent(query -> violations.add(violation(sourceFile, query,
                    "Из результата '" + query.getNameAsString() + "(...)' берут только идентификаторы: база отдает"
                            + " строки целиком, Hibernate собирает из них сущности, и все, кроме id, тут же"
                            + " выбрасывается; добавьте в репозиторий запрос, который возвращает только id"
                            + " (select e.id from ...)")));
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

    // Entity::getId либо entity -> entity.getId()
    private boolean takesId(Expression mapper) {
        if (mapper.isMethodReferenceExpr()) {
            return GET_ID.equals(mapper.asMethodReferenceExpr().getIdentifier());
        }
        if (!mapper.isLambdaExpr()) {
            return false;
        }
        LambdaExpr lambda = mapper.asLambdaExpr();
        return lambda.getParameters().size() == 1 && lambda.getExpressionBody()
                .map(Nodes::unwrap)
                .filter(Expression::isMethodCallExpr)
                .map(Expression::asMethodCallExpr)
                .filter(call -> GET_ID.equals(call.getNameAsString()) && call.getArguments().isEmpty())
                .flatMap(MethodCallExpr::getScope)
                .filter(scope -> scope.toString().equals(lambda.getParameter(0).getNameAsString()))
                .isPresent();
    }

    // map(...) стоит сразу за stream(), а stream() - сразу за запросом либо за переменной, которая больше не нужна
    private Optional<MethodCallExpr> findQuery(MethodCallExpr map) {
        List<MethodCallExpr> before = StreamChains.callsBefore(map);
        if (before.isEmpty() || !STREAM.equals(before.get(0).getNameAsString())) {
            return Optional.empty();
        }
        if (before.size() > 1) {
            return Optional.of(before.get(1)).filter(this::isSelection);
        }
        return before.get(0).getScope()
                .filter(scope -> scope.isNameExpr() && isUsedOnce(scope.asNameExpr()))
                .flatMap(LocalTypes::findInitializer)
                .map(Nodes::unwrap)
                .filter(Expression::isMethodCallExpr)
                .map(Expression::asMethodCallExpr)
                .filter(this::isSelection);
    }

    private boolean isSelection(MethodCallExpr call) {
        String name = call.getNameAsString();
        return Repositories.isRepositoryCall(call) && READ.matcher(name).matches() && !SINGLE.matcher(name).matches();
    }

    private boolean isUsedOnce(NameExpr variable) {
        return variable.findAncestor(CallableDeclaration.class)
                .filter(callable -> callable.findAll(NameExpr.class).stream()
                        .filter(usage -> usage.getNameAsString().equals(variable.getNameAsString()))
                        .count() == 1)
                .isPresent();
    }
}
