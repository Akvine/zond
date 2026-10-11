package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.exceptions.CatchReturnsDefaultRule;
import ru.akvine.zond.rules.exceptions.UnhandledRequestValueParseRule;
import ru.akvine.zond.rules.logical.OptionalPrimitiveParameterRule;
import ru.akvine.zond.rules.logical.TransactionalEventOutsideTransactionRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правила jr:397 - jr:400: запрос, который заканчивается ошибкой сервера или тишиной, - неразобранное значение,
 * сбой, превращенный в пустой ответ, необязательный параметр-примитив и событие вне транзакции
 */
class ErrorHandlingRulesTest {
    private static final String ORDER_CONTROLLER = """
            class OrderController {
                @GetMapping("/orders")
                List<Order> byStatus(@RequestParam String status, @RequestParam String id,
                                     @RequestParam String from) {
                    OrderStatus parsed = OrderStatus.valueOf(status);
                    UUID uuid = UUID.fromString(id);
                    LocalDate date = LocalDate.parse(from);
                    OrderStatus fixed = OrderStatus.valueOf("NEW");
                    return service.find(parsed, uuid, date);
                }
                @GetMapping("/safe")
                List<Order> safe(@RequestParam String status, @RequestParam String id) {
                    try {
                        OrderStatus parsed = OrderStatus.valueOf(status);
                        return service.find(parsed, UUID.fromString(id));
                    } catch (IllegalArgumentException e) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                    }
                }
                @GetMapping("/checked")
                OrderStatus checked(@RequestParam String status) {
                    if (!EnumUtils.isValidEnum(OrderStatus.class, status)) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                    }
                    return OrderStatus.valueOf(status);
                }
                OrderStatus internal() {
                    String stored = "NEW";
                    return OrderStatus.valueOf(stored);
                }
                @GetMapping("/number")
                Long number(@RequestParam String value) {
                    return Long.valueOf(value);
                }
            }
            enum OrderStatus { NEW, DONE }
            """;

    @TempDir
    Path dir;

    @Test
    void valueFromRequestMustBeParsedCarefully() {
        UnhandledRequestValueParseRule rule = new UnhandledRequestValueParseRule();
        List<Violation> violations = RuleTests.check(rule, ORDER_CONTROLLER);

        // Литерал, разбор в try с подходящим catch, проверенное заранее значение и значение не из запроса
        // ошибки запроса не дают; число из строки - забота другого правила
        assertThat(violations).extracting(Violation::line).containsExactly(5, 6, 7);
        assertThat(violations).extracting(Violation::confidence).containsOnly(Confidence.CONFIRMED);
        assertThat(violations.get(0).message())
                .contains("OrderStatus.valueOf(status)").contains("'status'").contains("IllegalArgumentException");
        assertThat(violations.get(2).message()).contains("DateTimeParseException");

        // Общий обработчик уже превращает IllegalArgumentException в понятный ответ; на дату он не рассчитан
        ProjectFixture withAdvice = new ProjectFixture(dir)
                .source("OrderController.java", ORDER_CONTROLLER)
                .source("Errors.java", """
                        @RestControllerAdvice
                        class Errors {
                            @ExceptionHandler(IllegalArgumentException.class)
                            ResponseEntity<String> badRequest(IllegalArgumentException e) {
                                return ResponseEntity.badRequest().body("bad value");
                            }
                        }
                        """);
        assertThat(withAdvice.lines(rule)).containsExactly("OrderController.java:7");
    }

    @Test
    void valueValidatedOnEveryPathIsNotReported() {
        ProjectFixture project = new ProjectFixture(dir)
                .source("ClientApi.java", """
                        interface ClientApi {
                            @PostMapping("/clients")
                            Response add(@Valid @RequestBody ClientRequest request);
                            @PutMapping("/clients")
                            Response update(@Valid @RequestBody ClientRequest request);
                            @PostMapping("/phones")
                            Response phone(@Valid @RequestBody PhoneRequest request);
                        }
                        """)
                .source("ClientController.java", """
                        @RestController
                        class ClientController implements ClientApi {
                            private ClientValidator clientValidator;
                            private ClientConverter clientConverter;
                            public Response add(ClientRequest request) {
                                return save(clientConverter.toClient(request));
                            }
                            public Response update(ClientRequest request) {
                                clientValidator.verify(request);
                                return save(clientConverter.toClient(request));
                            }
                            public Response phone(PhoneRequest request) {
                                clientValidator.verifyPhone(request);
                                return save(clientConverter.toPhone(request));
                            }
                        }
                        """)
                .source("ClientConverter.java", """
                        class ClientConverter {
                            Client toClient(ClientRequest request) {
                                Gender gender = Gender.valueOf(request.getGender());
                                Region region = Region.valueOf(request.getRegion());
                                return new Client(gender, region);
                            }
                            Phone toPhone(PhoneRequest request) {
                                return new Phone(PhoneKind.valueOf(request.getKind()));
                            }
                        }
                        """)
                .source("ClientRequest.java", """
                        class ClientRequest {
                            private String gender;
                            @Pattern(regexp = "NORTH|SOUTH")
                            private String region;
                        }
                        """)
                .source("PhoneRequest.java", "class PhoneRequest { private String kind; }")
                .source("Gender.java", "enum Gender { MALE, FEMALE }")
                .source("Region.java", "enum Region { NORTH, SOUTH }")
                .source("PhoneKind.java", "enum PhoneKind { MOBILE, HOME }");

        // Адрес и @RequestBody объявлены в интерфейсе контроллера - данные запроса все равно прослеживаются.
        // gender: путь через add идет мимо проверки, хотя update значение проверяет; region проверен
        // шаблоном на поле; kind проверен единственным, кто вызывает преобразование
        assertThat(project.lines(new UnhandledRequestValueParseRule())).containsExactly("ClientConverter.java:3");
    }

    @Test
    void failureMustNotLookLikeEmptyAnswer() {
        List<Violation> violations = RuleTests.check(new CatchReturnsDefaultRule(), """
                class Loader {
                    Config load(String path) {
                        try {
                            return mapper.readValue(new File(path), Config.class);
                        } catch (IOException e) {
                            log.error("failed", e);
                            return null;
                        }
                    }
                    Optional<User> user(String id) {
                        try {
                            return Optional.of(client.fetch(id));
                        } catch (RestClientException e) {
                            return Optional.empty();
                        }
                    }
                    List<Item> items() {
                        try {
                            return client.items();
                        } catch (TimeoutException | SQLException e) {
                            return Collections.emptyList();
                        }
                    }
                    Optional<User> find(Long id) {
                        try {
                            return Optional.of(repository.getOne(id));
                        } catch (EntityNotFoundException e) {
                            return Optional.empty();
                        }
                    }
                    Integer parse(String text) {
                        try {
                            return Integer.valueOf(text);
                        } catch (NumberFormatException e) {
                            return null;
                        }
                    }
                    Config wrapped(String path) {
                        try {
                            return mapper.readValue(new File(path), Config.class);
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    Config broad(String path) {
                        try {
                            return mapper.readValue(new File(path), Config.class);
                        } catch (Exception e) {
                            return null;
                        }
                    }
                    @Nullable
                    Config optional(String path) {
                        try {
                            return mapper.readValue(new File(path), Config.class);
                        } catch (IOException e) {
                            return null;
                        }
                    }
                    boolean send(Message message) {
                        try {
                            sender.send(message);
                            return true;
                        } catch (MailException e) {
                            return false;
                        }
                    }
                    Config fallback(String path) {
                        try {
                            return mapper.readValue(new File(path), Config.class);
                        } catch (IOException e) {
                            return Config.defaults();
                        }
                    }
                }
                """);

        // "Не нашлось" и "не разобралось" - законные поводы вернуть пустое; проброс, catch (Exception) (его
        // показывает другое правило), метод с @Nullable, признак успеха и настоящее значение по умолчанию - тоже
        assertThat(violations).extracting(Violation::line).containsExactly(5, 13, 20);
        assertThat(violations.get(0).message()).contains("catch (IOException)").contains("'null'");
        assertThat(violations.get(1).message()).contains("'Optional.empty()'");
        assertThat(violations.get(2).message()).contains("'Collections.emptyList()'");
    }

    @Test
    void optionalParameterCannotBePrimitive() {
        OptionalPrimitiveParameterRule rule = new OptionalPrimitiveParameterRule();
        List<Violation> violations = RuleTests.check(rule, """
                class SearchController {
                    @GetMapping("/search")
                    List<Item> search(@RequestParam(required = false) int page,
                                      @RequestParam(required = false, defaultValue = "20") int size,
                                      @RequestParam(required = false) Integer limit,
                                      @RequestParam boolean active,
                                      @RequestHeader(value = "X-Debug", required = false) boolean debug,
                                      @RequestParam(name = "q", required = false) String query) {
                        return List.of();
                    }
                    @GetMapping({"/items", "/items/{id}"})
                    Item item(@PathVariable(required = false) long id) { return null; }
                }
                """);

        // Значение по умолчанию и тип-обертка null переживают; обязательный параметр пустым не бывает
        assertThat(violations).extracting(Violation::line).containsExactly(3, 7, 12);
        assertThat(violations.get(0).message()).contains("'page'").contains("int").contains("Integer");
        assertThat(rule.confidence()).isEqualTo(Confidence.CONFIRMED);
    }

    @Test
    void transactionalListenerNeedsTransactionAtPublisher() {
        ProjectFixture project = new ProjectFixture(dir)
                .source("OrderService.java", """
                        class OrderService {
                            private ApplicationEventPublisher publisher;
                            private TransactionTemplate transactionTemplate;
                            public void pay(Order order) {
                                publisher.publishEvent(new OrderPaid(order));
                            }
                            @Transactional
                            public void payInTransaction(Order order) {
                                publisher.publishEvent(new OrderPaid(order));
                            }
                            @Transactional
                            public void outer(Order order) {
                                notifyPaid(order);
                            }
                            private void notifyPaid(Order order) {
                                publisher.publishEvent(new OrderPaid(order));
                            }
                            public void manual(Order order) {
                                transactionTemplate.executeWithoutResult(status -> publisher.publishEvent(new OrderPaid(order)));
                            }
                            public void other(Order order) {
                                publisher.publishEvent(new OrderShipped(order));
                            }
                            public void special(Order order) {
                                OrderPaidTwice event = new OrderPaidTwice(order);
                                publisher.publishEvent(event);
                            }
                            public void relaxed(Order order) {
                                publisher.publishEvent(new OrderCancelled(order));
                            }
                        }
                        """)
                .source("Listeners.java", """
                        class Listeners {
                            @TransactionalEventListener
                            void onPaid(OrderPaid event) {}
                            @EventListener
                            void onShipped(OrderShipped event) {}
                            @TransactionalEventListener(fallbackExecution = true)
                            void onCancelled(OrderCancelled event) {}
                        }
                        """)
                .source("OrderPaidTwice.java", "class OrderPaidTwice extends OrderPaid { }");

        TransactionalEventOutsideTransactionRule rule = new TransactionalEventOutsideTransactionRule();
        // Транзакция метода, вызывающего метода и открытая вручную считаются; обычный @EventListener и слушатель
        // с fallbackExecution = true транзакции не ждут; наследник события попадает к тому же слушателю
        assertThat(project.lines(rule)).containsExactly("OrderService.java:26", "OrderService.java:5");
        assertThat(rule.checkProject(project.context().sources()).get(0).message())
                .contains("OrderPaid").contains("'Listeners.onPaid'").contains("fallbackExecution");
    }
}
