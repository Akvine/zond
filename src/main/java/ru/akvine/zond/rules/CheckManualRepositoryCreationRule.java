package ru.akvine.zond.rules;

import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;

import java.util.Set;
import java.util.regex.Pattern;

@Component
public class CheckManualRepositoryCreationRule extends AbstractManualBeanCreationRule {
    // UserRepository, UserRepositoryImpl, UserDao, UserDaoImpl
    private static final Pattern REPOSITORY = Pattern.compile(".*(Repository|Dao|DAO)(Impl)?$");

    @Override
    public String code() {
        return RuleCodes.CHECK_MANUAL_REPOSITORY_CREATION_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет создание репозиториев через new в обход Spring";
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    @Override
    protected Pattern beanTypeName() {
        return REPOSITORY;
    }

    @Override
    protected Set<String> notBeanTypes() {
        return Set.of();
    }

    @Override
    protected String beanKind() {
        return "репозиторий";
    }
}
