package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.logical.CaughtExceptionInTransactionRule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правило jr:371 на цепочках внутри одного бина: свой метод, вызванный напрямую, выполняется в той же транзакции
 */
class CaughtExceptionInTransactionRuleTest {
    private static final String PAYMENT_SERVICE = """
            class PaymentService {
                @Transactional(propagation = Propagation.REQUIRED)
                public void charge(Order order) {
                    gateway.pay(order);
                }
            }
            """;
    private static final String AUDIT_SERVICE = """
            class AuditService {
                @Transactional(propagation = Propagation.REQUIRES_NEW)
                public void record(Order order) {
                    repository.save(new Audit(order));
                }
            }
            """;

    @TempDir
    Path dir;

    @Test
    void participantIsFoundBehindOwnMethod() {
        ProjectFixture project = new ProjectFixture(dir)
                .source("OrderService.java", """
                        class OrderService {
                            private PaymentService paymentService;
                            private AuditService auditService;
                            @Transactional
                            public void place(Order order) {
                                try {
                                    chargeQuietly(order);
                                } catch (Exception e) {
                                    log.warn("payment failed", e);
                                }
                            }
                            @Transactional
                            public void guarded(Order order) {
                                try {
                                    safeCharge(order);
                                } catch (Exception e) {
                                    log.warn("payment failed", e);
                                }
                            }
                            @Transactional
                            public void separate(Order order) {
                                try {
                                    auditQuietly(order);
                                } catch (Exception e) {
                                    log.warn("audit failed", e);
                                }
                            }
                            private void chargeQuietly(Order order) {
                                paymentService.charge(order);
                            }
                            private void safeCharge(Order order) {
                                try {
                                    paymentService.charge(order);
                                } catch (IllegalStateException e) {
                                    throw new OrderException(e);
                                }
                            }
                            private void auditQuietly(Order order) {
                                auditService.record(order);
                            }
                        }
                        """)
                .source("PaymentService.java", PAYMENT_SERVICE)
                .source("AuditService.java", AUDIT_SERVICE);
        CaughtExceptionInTransactionRule rule = new CaughtExceptionInTransactionRule();

        // Свой метод с собственным try и вызов в отдельной транзакции (REQUIRES_NEW) общую к откату не помечают
        List<Violation> violations = rule.checkProject(project.context().sources());
        assertThat(violations).extracting(Violation::line).containsExactly(8);
        assertThat(violations.get(0).message())
                .contains("'paymentService.charge' (через свой метод 'chargeQuietly')", "@Transactional-методе 'place'",
                        "UnexpectedRollbackException");
    }

    @Test
    void catchInPrivateMethodBelongsToCallerTransaction() {
        ProjectFixture project = new ProjectFixture(dir)
                .source("OrderService.java", """
                        class OrderService {
                            private PaymentService paymentService;
                            @Transactional
                            public void audit(Order order) {
                                record(order);
                            }
                            @Transactional
                            public void placeAndNotify(Order order) {
                                notifyQuietly(order);
                            }
                            public void outside(Order order) {
                                notifyQuietly(order);
                            }
                            private void record(Order order) {
                                try {
                                    paymentService.charge(order);
                                } catch (RuntimeException e) {
                                    log.warn("failed", e);
                                }
                            }
                            private void notifyQuietly(Order order) {
                                try {
                                    paymentService.charge(order);
                                } catch (RuntimeException e) {
                                    log.warn("failed", e);
                                }
                            }
                        }
                        """)
                .source("PaymentService.java", PAYMENT_SERVICE);
        CaughtExceptionInTransactionRule rule = new CaughtExceptionInTransactionRule();

        // notifyQuietly вызывают и без транзакции: там перехват ничему не мешает
        List<Violation> violations = rule.checkProject(project.context().sources());
        assertThat(violations).extracting(Violation::line).containsExactly(17);
        assertThat(violations.get(0).message())
                .contains("в методе 'record', который выполняется в транзакции метода 'audit'");
    }
}
