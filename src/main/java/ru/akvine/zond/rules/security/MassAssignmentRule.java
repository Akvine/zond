package ru.akvine.zond.rules.security;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractTaintRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Annotations;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.MethodCalls;
import ru.akvine.zond.rules.support.ProjectClasses;
import ru.akvine.zond.rules.support.Taint;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class MassAssignmentRule extends AbstractTaintRule {
    private static final String COPY_PROPERTIES = "copyProperties";
    private static final Set<String> COPIERS = Set.of("BeanUtils", "PropertyUtils", "BeanUtil");
    // У Apache Commons приемник идет первым аргументом, у Spring - вторым
    private static final String APACHE_PACKAGE = "org.apache.commons.beanutils";
    private static final String ENTITY = "Entity";
    private static final int COPY_ALL_ARGUMENTS = 2;

    private ProjectClasses classes;

    @Override
    public String code() {
        return RuleCodes.MASS_ASSIGNMENT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет копирование всех свойств объекта запроса в сущность: BeanUtils.copyProperties и подобные";
    }

    @Override
    public List<Violation> checkProject(List<SourceFile> sourceFiles) {
        classes = ProjectClasses.of(sourceFiles);
        return super.checkProject(sourceFiles);
    }

    @Override
    protected List<Violation> check(SourceFile sourceFile, Taint taint) {
        List<Violation> violations = new ArrayList<>();
        boolean apache = sourceFile.unit().getImports().stream()
                .anyMatch(declaration -> declaration.getNameAsString().startsWith(APACHE_PACKAGE));
        for (MethodCallExpr call : sourceFile.unit().findAll(MethodCallExpr.class)) {
            // Третий аргумент - список свойств, которые копировать нельзя: с ним копирование уже ограничено
            boolean copiesAll = COPY_PROPERTIES.equals(call.getNameAsString())
                    && call.getArguments().size() == COPY_ALL_ARGUMENTS
                    && call.getScope().map(MethodCalls::receiverName).filter(COPIERS::contains).isPresent();
            if (!copiesAll) {
                continue;
            }
            Expression source = call.getArgument(apache ? 1 : 0);
            Expression target = call.getArgument(apache ? 0 : 1);
            Optional<String> entity = entityName(target);
            if (entity.isEmpty()) {
                continue;
            }
            taint.findSource(source).ifPresent(input -> violations.add(violation(sourceFile, call,
                    "В сущность '" + entity.get() + "' копируются все свойства объекта из данных '" + input + "':"
                            + " клиент может добавить в запрос поле, которого нет в форме (role, id, balance), и оно"
                            + " попадет в базу; переносите поля явно либо перечислите запрещенные в ignoreProperties")));
        }
        return violations;
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.SECURITY;
    }

    private Optional<String> entityName(Expression target) {
        return LocalTypes.typeOf(target)
                .filter(type -> classes != null && classes.find(type)
                        .filter(found -> isEntity(found))
                        .isPresent());
    }

    private boolean isEntity(ClassOrInterfaceDeclaration type) {
        return Annotations.has(type, ENTITY);
    }
}
