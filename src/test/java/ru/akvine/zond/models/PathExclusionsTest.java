package ru.akvine.zond.models;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PathExclusionsTest {
    private static final Path ROOT = Path.of("project");

    @Test
    void nameExcludesDirectoryAtAnyDepth() {
        PathExclusions exclusions = PathExclusions.parse("generated");

        assertThat(matches(exclusions, "src/generated/Order.java")).isTrue();
        assertThat(matches(exclusions, "module/build/generated/deep/Order.java")).isTrue();
        assertThat(matches(exclusions, "generated")).isTrue();
        // Совпадать должно имя целиком, а не его часть
        assertThat(matches(exclusions, "src/generatedcode/Order.java")).isFalse();
        assertThat(matches(exclusions, "src/main/Order.java")).isFalse();
    }

    @Test
    void pathExcludesSubtree() {
        PathExclusions exclusions = PathExclusions.parse("src/main/java/legacy/, .\\src\\old");

        assertThat(matches(exclusions, "src/main/java/legacy/Order.java")).isTrue();
        assertThat(matches(exclusions, "src/main/java/legacy/deep/Order.java")).isTrue();
        assertThat(matches(exclusions, "src/old/Order.java")).isTrue();
        assertThat(matches(exclusions, "src/main/java/Order.java")).isFalse();
    }

    @Test
    void wildcardWithoutDirectoriesMatchesFileName() {
        PathExclusions exclusions = PathExclusions.parse("*Dto.java; Q?.java");

        assertThat(matches(exclusions, "src/main/java/OrderDto.java")).isTrue();
        assertThat(matches(exclusions, "OrderDto.java")).isTrue();
        assertThat(matches(exclusions, "src/Q1.java")).isTrue();
        assertThat(matches(exclusions, "src/main/java/Order.java")).isFalse();
        assertThat(matches(exclusions, "src/Q12.java")).isFalse();
    }

    @Test
    void doubleStarCrossesDirectories() {
        PathExclusions exclusions = PathExclusions.parse("**/generated/**, src/*/resources/*.java");

        assertThat(matches(exclusions, "a/b/generated/c/Order.java")).isTrue();
        assertThat(matches(exclusions, "generated/Order.java")).isTrue();
        assertThat(matches(exclusions, "src/main/resources/Order.java")).isTrue();
        // Одна звездочка через каталог не переходит
        assertThat(matches(exclusions, "src/main/java/resources/Order.java")).isFalse();
        assertThat(matches(exclusions, "a/b/Order.java")).isFalse();
    }

    @Test
    void emptyExclusionsMatchNothing() {
        assertThat(PathExclusions.parse(" , ;").isEmpty()).isTrue();
        assertThat(matches(PathExclusions.none(), "src/Order.java")).isFalse();
    }

    @Test
    void specialCharactersInPatternAreLiteral() {
        PathExclusions exclusions = PathExclusions.parse("*.g.java");

        assertThat(matches(exclusions, "src/Order.g.java")).isTrue();
        assertThat(matches(exclusions, "src/Orderxgxjava")).isFalse();
    }

    private boolean matches(PathExclusions exclusions, String file) {
        return exclusions.matches(ROOT, ROOT.resolve(file));
    }
}
