package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.logical.CheckByteArrayLoggingRule;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckByteArrayLoggingRuleTest {
    private final CheckByteArrayLoggingRule rule = new CheckByteArrayLoggingRule();

    @Test
    void findsArrayPassedToLogger() {
        List<Violation> violations = RuleTests.check(rule, """
                class Uploads {
                    void store(byte[] content, char[] password, MultipartFile file, String name, int[] ids) {
                        log.info("content {}", content);
                        log.debug("content: " + content);
                        log.info("file {}", file.getBytes());
                        log.warn("password {}", password);
                        log.info("size {}", content.length);
                        log.info("name {}", name);
                        log.info("ids {}", ids);
                        log.info("ids {}", Arrays.toString(ids));
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(3, 4, 5, 6);
        assertThat(violations.get(0).message()).contains("массив 'content'").contains("[B@");
    }

    @Test
    void findsWholeContentWrittenToLog() {
        List<Violation> violations = RuleTests.check(rule, """
                class Uploads {
                    void store(byte[] content, HttpServletRequest request) throws Exception {
                        log.info("content {}", new String(content, StandardCharsets.UTF_8));
                        log.info("content {}", Arrays.toString(content));
                        log.info("content {}", Base64.getEncoder().encodeToString(content));
                        log.info("body {}", new String(request.getInputStream().readAllBytes()));
                        log.info("hash {}", sha256(content));
                        log.info("text {}", new String("abc"));
                        String text = new String(content);
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(3, 4, 5, 6);
        assertThat(violations.get(0).message()).contains("все содержимое массива байтов");
    }

    @Test
    void ignoresOtherCallsAndTests() {
        assertThat(RuleTests.lines(rule, """
                class Uploads {
                    void store(byte[] content) {
                        writer.info(content);
                        output.write(content);
                    }
                }
                class UploadsTest {
                    @Test
                    void logs() {
                        byte[] content = new byte[0];
                        log.info("content {}", content);
                    }
                }
                """)).isEmpty();
    }
}
