package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.BusinessErrorAsBadRequestRule;
import ru.akvine.zond.rules.codesmell.CheckedBusinessExceptionRule;
import ru.akvine.zond.rules.exceptions.LostStackTraceInLogRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:467 и jr:468 и бизнес-исключения в jr:187: проверяемое исключение в бизнес-коде, код 400
 * для бизнес-отказа и запись бизнес-исключения в лог без стека
 */
class BusinessExceptionRulesTest {

    @TempDir
    Path dir;

    @Test
    void checkedExceptionBelongsToBoundary() {
        ProjectFixture project = new ProjectFixture(dir)
                .source("InsufficientFundsException.java", "class InsufficientFundsException extends Exception {}")
                .source("LimitExceededException.java", "class LimitExceededException extends InsufficientFundsException {}")
                .source("StorageException.java", "class StorageException extends Exception {}")
                .source("RateException.java", "class RateException extends Exception {}")
                .source("ExportException.java", "class ExportException extends Exception {}")
                .source("OrderNotFoundException.java", "class OrderNotFoundException extends RuntimeException {}")
                .source("UnusedException.java", "class UnusedException extends Exception {}")
                .source("AccountService.java", """
                        class AccountService {
                            void withdraw(Account account, BigDecimal amount) throws InsufficientFundsException {
                                if (account.getBalance().compareTo(amount) < 0) {
                                    throw new InsufficientFundsException();
                                }
                                if (amount.compareTo(account.getLimit()) > 0) {
                                    throw new LimitExceededException();
                                }
                                if (account.isClosed()) {
                                    throw new OrderNotFoundException();
                                }
                            }
                            byte[] load(Path file) throws StorageException {
                                try {
                                    return Files.readAllBytes(file);
                                } catch (IOException e) {
                                    throw new StorageException(e);
                                }
                            }
                            BigDecimal rate(String currency) throws RateException {
                                ResponseEntity<Rate> response = restTemplate.getForEntity("/rates/" + currency, Rate.class);
                                if (response.getBody() == null) {
                                    throw new RateException();
                                }
                                return response.getBody().value();
                            }
                        }
                        """)
                .source("ReportExporter.java", """
                        class ReportExporter {
                            void export(Report report) throws ExportException {
                                if (report.isEmpty()) {
                                    throw new ExportException();
                                }
                            }
                        }
                        """);
        CheckedBusinessExceptionRule rule = new CheckedBusinessExceptionRule();

        // Не считаются: исключение вокруг IOException, в методе с HTTP-вызовом, в классе-экспортере,
        // непроверяемое и то, которое в проекте никто не создает
        List<Violation> violations = rule.checkProject(project.context().sources());
        assertThat(violations).extracting(violation -> violation.file().getFileName().toString())
                .containsExactly("InsufficientFundsException.java");
        assertThat(violations.get(0).message())
                .contains("'InsufficientFundsException' бросают только из бизнес-кода", "AccountService.withdraw:4",
                        "AccountService.withdraw:7", "RuntimeException", "rollbackFor");
        assertThat(violations.get(0).errorLevel()).isEqualTo(ErrorLevel.MINOR);
        assertThat(rule.confidence()).isEqualTo(Confidence.PROBABLE);
    }

    @Test
    void businessFailureIsNotBadRequest() {
        ProjectFixture project = new ProjectFixture(dir)
                .source("InsufficientFundsException.java", "class InsufficientFundsException extends RuntimeException {}")
                .source("OrderAlreadyPaidException.java", """
                        @ResponseStatus(HttpStatus.BAD_REQUEST)
                        class OrderAlreadyPaidException extends RuntimeException {}
                        """)
                .source("LimitException.java", "class LimitException extends RuntimeException {}")
                .source("BlockedException.java", "class BlockedException extends RuntimeException {}")
                .source("InvalidPhoneException.java", """
                        @ResponseStatus(HttpStatus.BAD_REQUEST)
                        class InvalidPhoneException extends RuntimeException {}
                        """)
                .source("OrderNotFoundException.java", "class OrderNotFoundException extends RuntimeException {}")
                .source("Errors.java", """
                        @RestControllerAdvice
                        class Errors {
                            @ExceptionHandler(InsufficientFundsException.class)
                            ResponseEntity<String> funds(InsufficientFundsException e) {
                                return ResponseEntity.badRequest().body(e.getMessage());
                            }
                            @ExceptionHandler
                            @ResponseStatus(HttpStatus.BAD_REQUEST)
                            ErrorDto limit(LimitException e) {
                                return new ErrorDto(e.getMessage());
                            }
                            @ExceptionHandler(BlockedException.class)
                            ResponseEntity<String> blocked(BlockedException e) {
                                return ResponseEntity.status(400).body(e.getMessage());
                            }
                            @ExceptionHandler(MethodArgumentNotValidException.class)
                            ResponseEntity<String> invalid(MethodArgumentNotValidException e) {
                                return ResponseEntity.badRequest().body("Неверные данные");
                            }
                            @ExceptionHandler({InvalidPhoneException.class, OrderNotFoundException.class})
                            ResponseEntity<String> input(RuntimeException e) {
                                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
                            }
                            @ExceptionHandler(OrderAlreadyPaidException.class)
                            ResponseEntity<String> paid(OrderAlreadyPaidException e) {
                                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(e.getMessage());
                            }
                        }
                        """);
        BusinessErrorAsBadRequestRule rule = new BusinessErrorAsBadRequestRule();

        // Ошибки валидации и исключения с "говорящим" именем (Invalid..., ...NotFound) не считаются
        assertThat(project.lines(rule)).containsExactly(
                "Errors.java:14", "Errors.java:5", "Errors.java:8", "OrderAlreadyPaidException.java:1");
        List<Violation> violations = rule.checkProject(project.context().sources());
        assertThat(violations.get(0).message())
                .contains("Бизнес-исключение 'OrderAlreadyPaidException'", "с кодом 400", "422 Unprocessable Entity");
        assertThat(violations).allMatch(violation -> violation.errorLevel() == ErrorLevel.INFO);
    }

    @Test
    void plainBusinessFailureMayBeLoggedByText() {
        ProjectFixture project = new ProjectFixture(dir)
                .source("InsufficientFundsException.java", "class InsufficientFundsException extends RuntimeException {}")
                .source("PaymentFailedException.java", "class PaymentFailedException extends RuntimeException {}")
                .source("PaymentService.java", """
                        class PaymentService {
                            void withdraw(Account account, BigDecimal amount) {
                                if (account.getBalance().compareTo(amount) < 0) {
                                    throw new InsufficientFundsException("Недостаточно средств");
                                }
                            }
                            void charge(Order order) {
                                try {
                                    gateway.pay(order);
                                } catch (GatewayException e) {
                                    throw new PaymentFailedException("Платеж не прошел", e);
                                }
                            }
                        }
                        """)
                .source("OrderService.java", """
                        class OrderService {
                            void pay(Order order) {
                                try {
                                    paymentService.withdraw(order.getAccount(), order.getTotal());
                                } catch (InsufficientFundsException e) {
                                    log.warn("Заказ {} не оплачен: {}", order.getId(), e.getMessage());
                                }
                                try {
                                    paymentService.charge(order);
                                } catch (PaymentFailedException e) {
                                    log.warn("Заказ {} не оплачен: {}", order.getId(), e.getMessage());
                                }
                                try {
                                    notifier.send(order);
                                } catch (IllegalStateException e) {
                                    log.error("Уведомление не отправлено: {}", e.getMessage());
                                }
                            }
                        }
                        """);

        // Бизнес-исключение, которое бросают само по себе, причины не несет: его текста в логе достаточно.
        // PaymentFailedException создают в catch вокруг сбоя шлюза - без стека причину не увидеть
        assertThat(project.lines(new LostStackTraceInLogRule()))
                .containsExactly("OrderService.java:11", "OrderService.java:16");
    }
}
