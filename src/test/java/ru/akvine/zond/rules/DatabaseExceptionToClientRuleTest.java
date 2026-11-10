package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.security.DatabaseExceptionToClientRule;
import ru.akvine.zond.rules.security.ExceptionMessageToClientRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правило jr:462: подробности исключения базы данных в ответе клиенту
 */
class DatabaseExceptionToClientRuleTest {
    private final DatabaseExceptionToClientRule rule = new DatabaseExceptionToClientRule();

    @Test
    void handlerMustNotExposeDatabaseError() {
        List<Violation> violations = RuleTests.check(rule, """
                @RestControllerAdvice
                class Errors {
                    @ExceptionHandler(DataIntegrityViolationException.class)
                    ResponseEntity<String> conflict(DataIntegrityViolationException e) {
                        return ResponseEntity.status(409).body(e.getMostSpecificCause().getMessage());
                    }
                    @ExceptionHandler
                    ProblemDetail sql(SQLException e) {
                        ProblemDetail problem = ProblemDetail.forStatus(500);
                        problem.setDetail("Ошибка базы: " + e);
                        problem.setProperty("state", e.getSQLState());
                        return problem;
                    }
                    @ExceptionHandler({PersistenceException.class, JpaSystemException.class})
                    ResponseEntity<Object> persistence(NestedRuntimeException e) {
                        String message = ExceptionUtils.getRootCauseMessage(e);
                        return ResponseEntity.internalServerError().body(Map.of("error", message));
                    }
                    @ExceptionHandler(DataAccessException.class)
                    ResponseEntity<Object> whole(DataAccessException e) {
                        return ResponseEntity.status(500).body(e);
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(5, 10, 11, 16, 21);
        assertThat(violations.get(0).message())
                .contains("'e.getMostSpecificCause().getMessage()'", "DataIntegrityViolationException", "SQL-запрос");
        assertThat(violations.get(3).message()).contains("ExceptionUtils.getRootCauseMessage(e)", "NestedRuntimeException");
    }

    @Test
    void loggedOrCheckedDetailsStayInside() {
        assertThat(RuleTests.lines(rule, """
                import jakarta.validation.ConstraintViolationException;

                @RestControllerAdvice
                class Errors {
                    @ExceptionHandler(DataIntegrityViolationException.class)
                    ResponseEntity<String> conflict(DataIntegrityViolationException e) {
                        String errorId = UUID.randomUUID().toString();
                        String cause = e.getMostSpecificCause().getMessage();
                        log.error("Ошибка базы {}: {}", errorId, cause, e);
                        if (e.getMessage().contains("uk_users_email")) {
                            return ResponseEntity.status(409).body("Такой email уже занят");
                        }
                        if (e.getCause() instanceof PSQLException) {
                            return ResponseEntity.status(409).body("Запись уже существует");
                        }
                        return ResponseEntity.status(500).body("Внутренняя ошибка, код " + errorId);
                    }
                    @ExceptionHandler(ConstraintViolationException.class)
                    ResponseEntity<String> invalid(ConstraintViolationException e) {
                        return ResponseEntity.badRequest().body(e.getMessage());
                    }
                    @ExceptionHandler(OrderNotFoundException.class)
                    ResponseEntity<String> notFound(OrderNotFoundException e) {
                        return ResponseEntity.status(404).body(e.getMessage());
                    }
                    @ExceptionHandler(EntityNotFoundException.class)
                    ResponseEntity<String> missing(EntityNotFoundException e) {
                        return ResponseEntity.status(404).body(e.getMessage());
                    }
                }
                """)).isEmpty();
    }

    @Test
    void hibernateConstraintViolationIsDatabaseError() {
        assertThat(RuleTests.lines(rule, """
                import org.hibernate.exception.ConstraintViolationException;

                @RestControllerAdvice
                class Errors {
                    @ExceptionHandler(ConstraintViolationException.class)
                    ResponseEntity<String> constraint(ConstraintViolationException e) {
                        return ResponseEntity.status(409).body(e.getConstraintName());
                    }
                }
                """)).containsExactly(7);
    }

    @Test
    void controllerCatchMustNotReturnDatabaseError() {
        List<Violation> violations = RuleTests.check(rule, """
                @RestController
                class OrderController {
                    @PostMapping("/orders")
                    ResponseEntity<String> create(@RequestBody OrderRequest request) {
                        try {
                            orderService.create(request);
                            return ResponseEntity.ok("created");
                        } catch (DataAccessException e) {
                            return ResponseEntity.badRequest().body(e.getMessage());
                        }
                    }
                    @PutMapping("/orders")
                    void update(@RequestBody OrderRequest request) {
                        try {
                            orderService.update(request);
                        } catch (SQLException | PersistenceException e) {
                            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
                        }
                    }
                    @DeleteMapping("/orders/{id}")
                    void delete(@PathVariable Long id) {
                        try {
                            orderService.delete(id);
                        } catch (DataAccessException e) {
                            log.error("delete failed: {}", e.getMessage());
                            throw new OrderException("Не удалось удалить заказ", e);
                        }
                    }
                }
                @Service
                class OrderService {
                    void create(OrderRequest request) {
                        try {
                            repository.save(request);
                        } catch (DataAccessException e) {
                            return fallback(e.getMessage());
                        }
                    }
                }
                """);

        // В сервисе текст исключения клиенту не уходит: о контроллерах речь потому, что их ответ и есть ответ клиенту
        assertThat(violations).extracting(Violation::line).containsExactly(9, 17);
        assertThat(violations.get(0).message()).contains("catch контроллера", "DataAccessException");
    }

    @Test
    void broadHandlerIsLeftToGeneralRule() {
        String code = """
                @RestControllerAdvice
                class Errors {
                    @ExceptionHandler({SQLException.class, DataAccessException.class})
                    ResponseEntity<String> database(Exception e) {
                        return ResponseEntity.status(500).body(e.getMessage());
                    }
                }
                """;

        // Параметр общего типа: находка у jr:153, вторая здесь была бы повтором
        assertThat(RuleTests.lines(new ExceptionMessageToClientRule(), code)).containsExactly(5);
        assertThat(RuleTests.lines(rule, code)).isEmpty();
    }

    @Test
    void jaxRsMapperIsHandlerToo() {
        assertThat(RuleTests.lines(rule, """
                class DatabaseErrorMapper implements ExceptionMapper<SQLException> {
                    @Override
                    public Response toResponse(SQLException e) {
                        return Response.status(500).entity(e.getMessage()).build();
                    }
                }
                """)).containsExactly(4);
    }
}
