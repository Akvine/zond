package ru.akvine.zond.rules.support;

import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import lombok.experimental.UtilityClass;
import ru.akvine.zond.models.SourceFile;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Имена и строки, которые встречаются в коде проекта. По ним видно, настроено ли что-то в принципе -
 * очередь ошибок, срок жизни кеша, транзакционная отправка, - без разбора того, как именно это сделано.
 */
@UtilityClass
public class ProjectWords {
    // Слова нужны нескольким правилам подряд на одном и том же списке файлов - собираем их один раз на сканирование
    private static final PerSources<Set<String>> CACHE = new PerSources<>();

    /**
     * @return все имена (переменных, методов, типов, аннотаций) и строковые литералы проекта
     */
    public Set<String> of(List<SourceFile> sources) {
        return CACHE.get(sources, ProjectWords::collect);
    }

    private Set<String> collect(List<SourceFile> sources) {
        Set<String> words = new HashSet<>();
        for (SourceFile source : sources) {
            source.findAll(SimpleName.class).forEach(name -> words.add(name.getIdentifier()));
            // Имена аннотаций и импортов в дереве - отдельный вид узла
            source.findAll(Name.class).forEach(name -> words.add(name.getIdentifier()));
            source.findAll(StringLiteralExpr.class).forEach(literal -> words.add(literal.getValue()));
        }
        return Set.copyOf(words);
    }

    /**
     * @return true, если в коде проекта встречается хотя бы одно из слов
     */
    public boolean hasAny(List<SourceFile> sources, Set<String> wanted) {
        Set<String> words = of(sources);
        return wanted.stream().anyMatch(words::contains);
    }
}
