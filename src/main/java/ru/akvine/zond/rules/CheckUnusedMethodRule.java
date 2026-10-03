package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class CheckUnusedMethodRule extends AbstractUnusedDeclarationRule {
    // getName(), setName(...), isActive(): их вызывают по имени свойства Jackson, JPA, шаблоны и Spring
    private static final Pattern ACCESSOR = Pattern.compile("^(get|set|is)[A-Z].*");

    // Методы, которые вызывает сама JVM или стандартная библиотека
    private static final Set<String> STANDARD_METHODS = Set.of(
            "equals", "hashCode", "toString", "compareTo", "clone", "finalize", "close",
            "readObject", "writeObject", "readResolve", "writeReplace", "valueOf", "values");

    @Override
    public String code() {
        return RuleCodes.CHECK_UNUSED_METHOD_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет методы, которые нигде не вызываются";
    }

    @Override
    protected void check(
            SourceFile sourceFile, TypeDeclaration<?> type, boolean unused, ProjectUsages usages,
            List<Violation> violations) {
        // О неиспользуемом классе сообщает отдельное правило - перечислять еще и его методы незачем
        if (unused || isInsideUnused(type, usages)) {
            return;
        }
        for (MethodDeclaration method : type.getMethods()) {
            if (isCandidate(method, usages) && !usages.mayOverride(method, type) && !usages.isMethodUsed(method)) {
                violations.add(violation(sourceFile, method,
                        "Метод '" + type.getNameAsString() + "." + method.getNameAsString() + "' нигде в проекте"
                                + " не вызывается: мертвый код приходится читать и сопровождать впустую;"
                                + " удалите его. Если его вызывают извне (другой модуль, рефлексия, шаблон),"
                                + " скройте находку комментарием zond:ignore"));
            }
        }
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    // Приватные методы проверяет отдельное правило в пределах файла. Метод с аннотацией (@GetMapping, @Bean,
    // @Scheduled, @Override) вызывает фреймворк либо код через тип предка
    private boolean isCandidate(MethodDeclaration method, ProjectUsages usages) {
        String name = method.getNameAsString();
        return !method.isPrivate()
                && !usages.isFrameworkEntry(method)
                && !isMain(method)
                && !ACCESSOR.matcher(name).matches()
                && !STANDARD_METHODS.contains(name);
    }
}
