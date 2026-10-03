package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import lombok.experimental.UtilityClass;

import java.util.regex.Pattern;

@UtilityClass
class Repositories {
    private final static String REPOSITORY_ANNOTATION = "Repository";

    // Имя объекта либо типа: userRepository, orderDao, JpaRepository
    private final static Pattern REPOSITORY = Pattern.compile(".*(repository|repo|dao)$", Pattern.CASE_INSENSITIVE);

    // USER_REPOSITORY - константа, а не внедренный бин
    private final static Pattern CONSTANT_NAME = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    boolean isRepository(String receiver) {
        return REPOSITORY.matcher(receiver).matches() && !CONSTANT_NAME.matcher(receiver).matches();
    }

    /**
     * @return true для вызова вида userRepository.findById(...)
     */
    boolean isRepositoryCall(MethodCallExpr call) {
        return call.getScope().filter(Repositories::isRepositoryObject).isPresent();
    }

    // По типу: сам он либо его предок - репозиторий (interface Users extends JpaRepository), либо на нем @Repository.
    // Если тип неизвестен - по имени объекта
    private boolean isRepositoryObject(Expression scope) {
        if (Types.annotations(scope).contains(REPOSITORY_ANNOTATION)) {
            return true;
        }
        return Types.matches(scope, type -> REPOSITORY.matcher(type).matches())
                .orElseGet(() -> isRepository(MethodCalls.receiverName(scope)));
    }
}
