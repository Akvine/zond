package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import ru.akvine.zond.models.Violation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckUnclosedResourceRuleTest {
    private final CheckUnclosedResourceRule rule = new CheckUnclosedResourceRule();

    @Test
    void findsResourcesThatAreNeverClosed() {
        List<Violation> violations = RuleTests.check(rule, """
                class Sample {
                    void leak(Path path) throws Exception {
                        FileInputStream in = new FileInputStream("a.txt");
                        in.read();
                        Connection connection = dataSource.getConnection();
                        connection.commit();
                        long count = Files.lines(path).filter(line -> !line.isEmpty()).count();
                    }
                    void ok(Path path) throws Exception {
                        try (FileInputStream in = new FileInputStream("a.txt")) {
                            in.read();
                        }
                        FileInputStream closed = new FileInputStream("a.txt");
                        closed.close();
                        FileReader wrapped = new FileReader("a.txt");
                        BufferedReader reader = new BufferedReader(wrapped);
                        try (reader) {
                            reader.readLine();
                        }
                        Scanner console = new Scanner(System.in);
                        console.nextLine();
                        try (Stream<String> lines = Files.lines(path)) {
                            lines.count();
                        }
                        try (Stream<String> filtered = Files.lines(path).filter(line -> !line.isEmpty())) {
                            filtered.count();
                        }
                    }
                    InputStream open() throws Exception {
                        InputStream stream = new FileInputStream("a.txt");
                        return stream;
                    }
                }
                """);

        assertThat(violations).extracting(Violation::line).containsExactly(3, 5, 7);
        assertThat(violations).allMatch(violation -> violation.ruleCode().equals("jr:17"));
        assertThat(violations.get(0).message()).contains("'in'");
        assertThat(violations.get(2).message()).contains("Files.lines");
    }
}
