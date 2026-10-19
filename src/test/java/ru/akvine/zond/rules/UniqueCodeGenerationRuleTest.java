package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.logical.UniqueCodeGenerationRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правило jr:425: подбор уникального случайного кода без предела попыток либо без исключения после последней
 */
class UniqueCodeGenerationRuleTest {
    private final UniqueCodeGenerationRule rule = new UniqueCodeGenerationRule();

    @Test
    void boundedAttemptsWithExceptionAreCorrect() {
        // Как TeamUtils.generateUuid() в tips-proxy: предел попыток из настроек и исключение после цикла
        String code = """
                class TeamUtils {
                    public String generateUuid(String bankId) {
                        Integer length = properties.getTeamUuidGenerationLength(bankId);
                        Integer attempts = properties.getTeamUuidGenerationAttempts(bankId);
                        for (int i = 0; i < attempts; i++) {
                            String uuid = RandomStringUtils.randomNumeric(length);
                            Optional<TeamEntity> team = teamRepository.findByUuidAndBankIdWithDeleted(uuid, bankId);
                            if (team.isPresent()) {
                                continue;
                            }
                            return uuid;
                        }
                        throw new SecurityException("Couldn't create unique team uuid " + attempts + " times");
                    }
                    public String generateCode() {
                        int attempts = 0;
                        while (true) {
                            String code = RandomStringUtils.randomAlphanumeric(6);
                            if (!repository.existsByCode(code)) {
                                return code;
                            }
                            if (++attempts > MAX_ATTEMPTS) {
                                throw new IllegalStateException("Не удалось подобрать код");
                            }
                        }
                    }
                }
                """;

        assertThat(RuleTests.lines(rule, code)).isEmpty();
    }

    @Test
    void endlessLoopUntilFreeCode() {
        String code = """
                class InviteService {
                    String inviteCode() {
                        while (true) {
                            String code = RandomStringUtils.randomNumeric(6);
                            if (!inviteRepository.existsByCode(code)) {
                                return code;
                            }
                        }
                    }
                    String pinCode() {
                        String pin;
                        do {
                            pin = String.valueOf(random.nextInt(10000));
                        } while (cardRepository.findByPin(pin).isPresent());
                        return pin;
                    }
                    String shortId() {
                        String id;
                        do {
                            id = UUID.randomUUID().toString().substring(0, 8);
                        } while (repository.existsById(id));
                        return id;
                    }
                }
                """;

        List<Violation> violations = RuleTests.check(rule, code);
        assertThat(violations).extracting(Violation::line).containsExactly(3, 12, 19);
        assertThat(violations.get(0).message()).contains("без предела попыток", "inviteRepository.existsByCode(code)");
        // Миллион и десять тысяч вариантов кончаются на реальных объемах
        assertThat(violations.get(0).confidence()).isEqualTo(Confidence.PROBABLE);
    }

    @Test
    void newCommonsLangApi() {
        String code = """
                class Pins {
                    String pin() {
                        String pin;
                        do {
                            pin = RandomStringUtils.secure().nextNumeric(4);
                        } while (pinRepository.existsByValue(pin));
                        return pin;
                    }
                }
                """;

        assertThat(RuleTests.check(rule, code)).singleElement().satisfies(violation -> {
            assertThat(violation.line()).isEqualTo(4);
            assertThat(violation.confidence()).isEqualTo(Confidence.PROBABLE);
        });
    }

    @Test
    void exhaustedAttemptsWithoutException() {
        String code = """
                class Codes {
                    String generate() {
                        for (int attempt = 0; attempt < 5; attempt++) {
                            String code = RandomStringUtils.randomAlphanumeric(8);
                            if (!repository.existsByCode(code)) {
                                return code;
                            }
                        }
                        return null;
                    }
                    void assign(Order order) {
                        String code = null;
                        for (int attempt = 0; attempt < 5; attempt++) {
                            code = RandomStringUtils.randomNumeric(6);
                            if (!repository.existsByCode(code)) {
                                break;
                            }
                        }
                        order.setCode(code);
                    }
                    String withCheckAfterLoop() {
                        String found = null;
                        for (int attempt = 0; attempt < 5 && found == null; attempt++) {
                            String code = RandomStringUtils.randomNumeric(6);
                            if (!repository.existsByCode(code)) {
                                found = code;
                                break;
                            }
                        }
                        if (found == null) {
                            throw new IllegalStateException("Коды закончились");
                        }
                        return found;
                    }
                }
                """;

        List<Violation> violations = RuleTests.check(rule, code);
        assertThat(violations).extracting(Violation::line).containsExactly(3, 13);
        assertThat(violations.get(0).message()).contains("После последней попытки", "исключение");
    }

    @Test
    void codeBuiltCharByCharAndLongSuffix() {
        String code = """
                class Names {
                    String code() {
                        while (true) {
                            StringBuilder builder = new StringBuilder();
                            for (int i = 0; i < 6; i++) {
                                builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
                            }
                            String candidate = builder.toString();
                            if (!usedCodes.contains(candidate)) {
                                return candidate;
                            }
                        }
                    }
                    String uniquify(String baseName, Set<String> existingNames) {
                        String newName;
                        do {
                            String suffix = StringUtils.randomAlphanumeric(6);
                            newName = baseName + "-" + suffix;
                        } while (existingNames.contains(newName));
                        return newName;
                    }
                }
                """;

        List<Violation> violations = RuleTests.check(rule, code);
        assertThat(violations).extracting(Violation::line).containsExactly(3, 16);
        // 62^6 вариантов на практике не кончаются: находка остается, но как подозрение
        assertThat(violations.get(1).confidence()).isEqualTo(Confidence.SUSPICION);
    }

    @Test
    void notUniquenessSearch() {
        String code = """
                class Other {
                    List<String> batch(int count) {
                        List<String> codes = new ArrayList<>();
                        for (int i = 0; i < count; i++) {
                            String code = RandomStringUtils.randomNumeric(6);
                            if (!usedCodes.contains(code)) {
                                codes.add(code);
                            }
                        }
                        return codes;
                    }
                    String uuid() {
                        String id;
                        do {
                            id = UUID.randomUUID().toString();
                        } while (repository.existsById(id));
                        return id;
                    }
                    void waitForLock(String key) throws InterruptedException {
                        while (!locks.contains(key)) {
                            Thread.sleep(random.nextInt(100));
                        }
                    }
                }
                """;

        // Пакет из count кодов, полный UUID и пауза со случайной длиной - не подбор свободного кода
        assertThat(RuleTests.lines(rule, code)).isEmpty();
    }
}
