package ru.akvine.zond.models;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.enums.ErrorLevel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScanOptionsTest {

    @Test
    void defaultsAllowEverything() {
        ScanOptions options = ScanOptions.defaults();

        assertThat(options.allows("jr:40", "CheckMagicNumberRule", ErrorLevel.INFO)).isTrue();
        assertThat(options.allows("jr:1", "CheckTransactionOnPrivateMethodRule", ErrorLevel.CRITICAL)).isTrue();
    }

    @Test
    void disablesRulesByCodeAndByName() {
        ScanOptions options = ScanOptions.parse(" JR:40, checkTodoCommentRule ;jr:41", "");

        assertThat(options.allows("jr:40", "CheckMagicNumberRule", ErrorLevel.INFO)).isFalse();
        assertThat(options.allows("jr:41", "CheckTodoCommentRule", ErrorLevel.INFO)).isFalse();
        assertThat(options.allows("jr:42", "CheckNamingConventionRule", ErrorLevel.INFO)).isTrue();
    }

    @Test
    void keepsOnlyLevelsAtOrAboveThreshold() {
        ScanOptions options = ScanOptions.parse("", "major");

        assertThat(options.allows("jr:1", "A", ErrorLevel.BLOCKER)).isTrue();
        assertThat(options.allows("jr:1", "A", ErrorLevel.CRITICAL)).isTrue();
        assertThat(options.allows("jr:1", "A", ErrorLevel.MAJOR)).isTrue();
        assertThat(options.allows("jr:1", "A", ErrorLevel.MINOR)).isFalse();
        assertThat(options.allows("jr:1", "A", ErrorLevel.INFO)).isFalse();
    }

    @Test
    void rejectsUnknownLevelWithListOfValidOnes() {
        assertThatThrownBy(() -> ScanOptions.parse("", "HIGH"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'HIGH'")
                .hasMessageContaining("BLOCKER, CRITICAL, MAJOR, MINOR, INFO");
    }
}
