package ru.akvine.zond.rules;

import com.github.javaparser.ast.body.TypeDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;

import java.util.List;

@Component
public class CheckUnusedClassRule extends AbstractUnusedDeclarationRule {

    @Override
    public String code() {
        return RuleCodes.CHECK_UNUSED_CLASS_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует проект и ищет классы, на которые нигде нет ссылок";
    }

    @Override
    protected void check(
            SourceFile sourceFile, TypeDeclaration<?> type, boolean unused, ProjectUsages usages,
            List<Violation> violations) {
        if (unused && !isInsideUnused(type, usages)) {
            violations.add(violation(sourceFile, type,
                    "Класс '" + type.getNameAsString() + "' нигде в проекте не используется: мертвый код"
                            + " приходится читать и сопровождать впустую; удалите его. Если им пользуются извне"
                            + " (другой модуль, рефлексия, файл настроек), скройте находку комментарием zond:ignore"));
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
