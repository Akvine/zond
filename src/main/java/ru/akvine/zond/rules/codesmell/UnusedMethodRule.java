package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractUnusedDeclarationRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.ProjectUsages;

import java.util.List;
import java.util.Set;

@Component
public class UnusedMethodRule extends AbstractUnusedDeclarationRule {

    @Override
    public String code() {
        return RuleCodes.UNUSED_METHOD_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет методы, которые нигде не вызываются либо вызываются только"
                + " из такого же неиспользуемого кода";
    }

    @Override
    protected void check(
            SourceFile sourceFile, TypeDeclaration<?> type, ProjectUsages usages, List<Violation> violations) {
        // О неиспользуемом классе сообщает отдельное правило - перечислять еще и его методы незачем
        if (usages.isTypeDead(type) || isInsideDead(type, usages)) {
            return;
        }
        for (MethodDeclaration method : type.getMethods()) {
            if (!usages.isMethodDead(method)) {
                continue;
            }

            // Приватный метод без единого вызова находит отдельное правило в пределах файла
            Set<String> callers = usages.callersOf(method);
            if (method.isPrivate() && callers.isEmpty()) {
                continue;
            }

            // Метод, который вызывается только из мертвого кода, сам мертв: удалять их нужно вместе
            String reason = callers.isEmpty()
                    ? "нигде в проекте не вызывается"
                    : "вызывается только из неиспользуемого кода (" + String.join(", ", callers) + ")";
            violations.add(violation(sourceFile, method,
                    "Метод '" + type.getNameAsString() + "." + method.getNameAsString() + "' " + reason
                            + ": мертвый код приходится читать и сопровождать впустую;"
                            + " удалите его. Если его вызывают извне (другой модуль, рефлексия, шаблон),"
                            + " скройте находку комментарием zond:ignore"));
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
}
