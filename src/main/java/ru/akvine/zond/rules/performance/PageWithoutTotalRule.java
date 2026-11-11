package ru.akvine.zond.rules.performance;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.ProjectRule;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.Repositories;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * repository.findByStatus(status, pageable).getContent(): метод возвращает Page, и ради totalElements Spring Data
 * выполняет второй запрос - COUNT по тем же условиям. Здесь его результат сразу выбрасывают.
 */
@Component
public class PageWithoutTotalRule extends AbstractRule implements ProjectRule {
    private static final String GET_CONTENT = "getContent";
    private static final String FIND_ALL = "findAll";
    private static final String PAGE = "Page";
    private static final Pattern PAGE_ARGUMENT = Pattern.compile("(?i).*page(able|request)?$|^PageRequest\\.of\\(.*");
    private static final Pattern REPOSITORY_TYPE = Pattern.compile(".*(Repository|Repo|Dao|DAO)$");

    @Override
    public String code() {
        return RuleCodes.PAGE_WITHOUT_TOTAL_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет запрос страницы Page, от которой берут только содержимое: запрос COUNT"
                + " выполняется зря";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        // Методы репозиториев проекта: какие возвращают Page, а какие - Slice или List
        Set<String> pageMethods = new HashSet<>();
        Set<String> otherMethods = new HashSet<>();
        for (SourceFile sourceFile : sourceFiles) {
            sourceFile.unit().findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(type -> type.isInterface() && REPOSITORY_TYPE.matcher(type.getNameAsString()).matches())
                    .flatMap(type -> type.getMethods().stream())
                    .forEach(method -> collect(method, pageMethods, otherMethods));
        }
        List<Violation> violations = new ArrayList<>();
        for (SourceFile sourceFile : sourceFiles) {
            for (MethodCallExpr content : sourceFile.unit().findAll(MethodCallExpr.class)) {
                if (!GET_CONTENT.equals(content.getNameAsString()) || !content.getArguments().isEmpty()
                        || TestClasses.isInside(content)) {
                    continue;
                }
                Optional<MethodCallExpr> query = content.getScope()
                        .map(Nodes::unwrap)
                        .filter(Expression::isMethodCallExpr)
                        .map(Expression::asMethodCallExpr)
                        .filter(call -> returnsPage(call, pageMethods, otherMethods));
                if (query.isPresent()) {
                    violations.add(violation(sourceFile, content.getName(),
                            "От страницы '" + query.get().getNameAsString() + "(...)' берут только"
                                    + " getContent(): ради числа всех записей Spring Data выполнил второй запрос"
                                    + " COUNT, и его результат выброшен; объявите у метода тип Slice или List"));
                }
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

    private void collect(MethodDeclaration method, Set<String> pageMethods, Set<String> otherMethods) {
        boolean page = PAGE.equals(LocalTypes.typeName(method.getType()));
        (page ? pageMethods : otherMethods).add(method.getNameAsString());
    }

    // Свой метод репозитория с типом Page либо унаследованный findAll(pageable): он возвращает Page всегда
    private boolean returnsPage(MethodCallExpr query, Set<String> pageMethods, Set<String> otherMethods) {
        boolean paged = query.getArguments().stream()
                .anyMatch(argument -> PAGE_ARGUMENT.matcher(argument.toString()).matches());
        if (!paged || !Repositories.isRepositoryCall(query)) {
            return false;
        }
        String name = query.getNameAsString();
        return pageMethods.contains(name) && !otherMethods.contains(name)
                || FIND_ALL.equals(name) && !otherMethods.contains(name);
    }
}
