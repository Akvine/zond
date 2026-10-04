package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.expr.AnnotationExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.List;

@Component
public class CheckManyToManyRule extends AbstractRule {
    private static final String MANY_TO_MANY = "ManyToMany";

    @Override
    public String code() {
        return RuleCodes.CHECK_MANY_TO_MANY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет связи @ManyToMany";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        return sourceFile.unit().findAll(AnnotationExpr.class).stream()
                .filter(annotation -> MANY_TO_MANY.equals(annotation.getName().getIdentifier()))
                .map(annotation -> violation(sourceFile, annotation,
                        "@ManyToMany: промежуточная таблица скрыта, в нее нельзя добавить свои поля (дату, статус),"
                                + " а изменение коллекции Hibernate часто выполняет удалением и повторной вставкой"
                                + " всех строк; заведите сущность для промежуточной таблицы и свяжите через"
                                + " @OneToMany и @ManyToOne"))
                .toList();
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
