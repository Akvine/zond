package ru.akvine.zond.rules;

import com.github.javaparser.ast.expr.MethodCallExpr;
import lombok.experimental.UtilityClass;

import java.util.regex.Pattern;

@UtilityClass
class Repositories {
    // Типы не разрешаем, поэтому репозиторий узнаем по имени объекта
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
        return call.getScope().map(MethodCalls::receiverName).filter(Repositories::isRepository).isPresent();
    }
}
