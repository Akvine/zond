package ru.akvine.zond.rules.exceptions;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * flux.subscribe() и mono.subscribe(value -> ...) без обработчика ошибки: в Reactor ошибка уходит в
 * onErrorDropped и теряется, в RxJava - превращается в OnErrorNotImplementedException в чужом потоке.
 * Отправка, сохранение или уведомление молча не происходят
 */
@Component
public class SubscribeWithoutErrorHandlerRule extends AbstractRule {
    private static final String SUBSCRIBE = "subscribe";
    // subscribe(), subscribe(onNext): ошибку обработать некому
    private static final int MAX_ARGUMENTS_WITHOUT_ERROR_HANDLER = 1;
    private static final Set<String> REACTIVE_TYPES = Set.of(
            "Mono", "Flux", "Flowable", "Observable", "Single", "Maybe", "Completable", "ParallelFlux");
    private static final List<String> REACTIVE_PACKAGES = List.of("reactor.core.publisher", "io.reactivex");
    // Ошибка уже обработана выше по цепочке
    private static final Set<String> ERROR_OPERATORS = Set.of(
            "onErrorResume", "onErrorReturn", "onErrorContinue", "onErrorComplete", "doOnError", "onErrorMap",
            "onErrorResumeNext", "onErrorReturnItem");
    // subscribe(subscriber) с объектом-подписчиком: у него свой onError
    private static final Set<String> SUBSCRIBER_TYPES = Set.of("Subscriber", "CoreSubscriber", "BaseSubscriber",
            "Observer", "DisposableObserver", "SingleObserver");

    @Override
    public String code() {
        return RuleCodes.SUBSCRIBE_WITHOUT_ERROR_HANDLER_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет subscribe() у Mono, Flux и типов RxJava без обработчика ошибки";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        if (!usesReactiveLibrary(sourceFile.unit())) {
            return List.of();
        }
        return sourceFile.unit().findAll(MethodCallExpr.class).stream()
                .filter(call -> SUBSCRIBE.equals(call.getNameAsString())
                        && call.getArguments().size() <= MAX_ARGUMENTS_WITHOUT_ERROR_HANDLER)
                .filter(call -> call.getScope().isPresent() && isReactive(call.getScope().get()))
                .filter(call -> !handlesErrorsUpstream(call) && !passesSubscriber(call))
                .filter(call -> !TestClasses.isInside(call))
                .map(call -> violation(sourceFile, call,
                        "'" + call.getNameAsString() + "(...)' без обработчика ошибки: если цепочка упадет,"
                                + " ошибка потеряется (в Reactor - onErrorDropped, в RxJava -"
                                + " OnErrorNotImplementedException в чужом потоке), и действие молча не случится;"
                                + " передайте обработчик вторым аргументом subscribe либо добавьте doOnError"))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MAJOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.EXCEPTION;
    }

    private boolean usesReactiveLibrary(CompilationUnit unit) {
        return unit.getImports().stream()
                .map(ImportDeclaration::getNameAsString)
                .anyMatch(name -> REACTIVE_PACKAGES.stream().anyMatch(name::startsWith));
    }

    // Тип известен - по типу; иначе по цепочке: Mono.just(...), Flux.fromIterable(...), webClient...bodyToMono(...)
    private boolean isReactive(Expression scope) {
        Optional<String> type = LocalTypes.typeOf(scope);
        if (type.isPresent()) {
            return REACTIVE_TYPES.stream().anyMatch(reactive -> type.get().equals(reactive)
                    || type.get().startsWith(reactive + "<"));
        }
        String text = scope.toString();
        return REACTIVE_TYPES.stream().anyMatch(reactive -> text.startsWith(reactive + "."))
                || text.contains("bodyToMono") || text.contains("bodyToFlux");
    }

    private boolean handlesErrorsUpstream(MethodCallExpr call) {
        Node current = call.getScope().orElse(null);
        while (current instanceof MethodCallExpr step) {
            if (ERROR_OPERATORS.contains(step.getNameAsString())) {
                return true;
            }
            current = step.getScope().orElse(null);
        }
        return false;
    }

    private boolean passesSubscriber(MethodCallExpr call) {
        if (call.getArguments().isEmpty()) {
            return false;
        }
        Expression argument = call.getArgument(0);
        if (argument.isLambdaExpr() || argument.isMethodReferenceExpr()) {
            return false;
        }
        // upstream.subscribe(this): класс сам реализует Observer / Subscriber вместе с onError
        if (argument.isObjectCreationExpr() || argument.isThisExpr()) {
            return true;
        }
        return LocalTypes.typeOf(argument)
                .filter(type -> SUBSCRIBER_TYPES.stream().anyMatch(type::contains))
                .isPresent();
    }
}
