package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;

import java.util.ArrayList;
import java.util.List;

/**
 * Массив в записи. equals и hashCode, которые компилятор пишет для record, сравнивают компоненты через
 * equals, а у массива это сравнение ссылок: две записи с одинаковым содержимым массива не равны.
 */
@Component
public class RecordWithArrayRule extends AbstractRule {
    private static final String EQUALS = "equals";

    @Override
    public String code() {
        return RuleCodes.RECORD_WITH_ARRAY_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет record с компонентом-массивом без своего equals: записи сравниваются"
                + " по ссылке на массив, а не по содержимому";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<Violation> violations = new ArrayList<>();
        for (RecordDeclaration record : sourceFile.unit().findAll(RecordDeclaration.class)) {
            boolean ownEquals = record.getMethodsByName(EQUALS).stream()
                    .anyMatch(method -> method.getParameters().size() == 1);
            if (ownEquals) {
                continue;
            }
            for (Parameter component : record.getParameters()) {
                if (component.getType().isArrayType() || component.isVarArgs()) {
                    violations.add(violation(sourceFile, component,
                            "Компонент-массив '" + component.getNameAsString() + "' записи '" + record.getNameAsString()
                                    + "': equals и hashCode записи сравнивают массив по ссылке, а не по содержимому -"
                                    + " две записи с одинаковыми данными не равны, в HashSet и ключах Map они не"
                                    + " находятся, а toString печатает адрес. К тому же массив можно изменить"
                                    + " снаружи; замените его на List (List.copyOf в компактном конструкторе) либо"
                                    + " переопределите equals, hashCode и toString через Arrays.equals,"
                                    + " Arrays.hashCode и Arrays.toString"));
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
        return ErrorType.LOGICAL;
    }
}
