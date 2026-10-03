package ru.akvine.zond.rules;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQL / JPQL-запрос на изменение данных без условия WHERE.
 * Проверяются только строки, собранные из одних литералов: если к запросу приклеивается переменная,
 * условие может дописываться в ней.
 */
public abstract class AbstractSqlWithoutWhereRule extends AbstractRule {
    private static final Pattern WHERE = Pattern.compile("\\bwhere\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final Set<String> QUERY_ANNOTATIONS = Set.of("Query", "NamedQuery", "NamedNativeQuery", "SQLDelete");
    private static final Set<String> QUERY_METHODS = Set.of(
            "update", "batchUpdate", "execute", "executeUpdate", "executeQuery", "addBatch", "prepareStatement",
            "createQuery", "createNativeQuery", "query");
    private static final Pattern QUERY_VARIABLE = Pattern.compile(".*(sql|query|hql|jpql).*", Pattern.CASE_INSENSITIVE);

    /**
     * @return шаблон, которому соответствует запрос этого вида целиком
     */
    protected abstract Pattern statement();

    /**
     * @return текст нарушения
     */
    protected abstract String message(String sql);

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return StringLiterals.findComplete(sourceFile.unit()).stream()
                .filter(literal -> isSqlContext(literal.node()))
                .filter(literal -> {
                    String sql = normalize(literal.text());
                    return statement().matcher(sql).matches() && !WHERE.matcher(sql).find();
                })
                .map(literal -> violation(sourceFile, literal.node(), message(normalize(literal.text()))))
                .toList();
    }

    // Строка должна стоять там, где ждут запрос: иначе под правило попадет сообщение вида "delete from list failed"
    private boolean isSqlContext(Node literal) {
        Node parent = literal.getParentNode().orElse(null);
        while (parent instanceof EnclosedExpr || parent instanceof MemberValuePair) {
            parent = parent.getParentNode().orElse(null);
        }

        if (parent instanceof AnnotationExpr annotation) {
            return QUERY_ANNOTATIONS.contains(annotation.getName().getIdentifier());
        }
        if (parent instanceof MethodCallExpr call) {
            return QUERY_METHODS.contains(call.getNameAsString());
        }
        return parent instanceof VariableDeclarator variable
                && QUERY_VARIABLE.matcher(variable.getNameAsString()).matches();
    }

    private String normalize(String sql) {
        return WHITESPACE.matcher(sql).replaceAll(" ").trim();
    }
}
