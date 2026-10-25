package ru.akvine.zond.rules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.akvine.zond.enums.Confidence;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.codesmell.ConsoleOutputRule;
import ru.akvine.zond.rules.codesmell.MagicNumberRule;
import ru.akvine.zond.rules.codesmell.TestWithoutAssertionRule;
import ru.akvine.zond.rules.codesmell.UnusedClassRule;
import ru.akvine.zond.rules.codesmell.UnusedMethodRule;
import ru.akvine.zond.rules.exceptions.UnhandledNumberFormatRule;
import ru.akvine.zond.rules.logical.CastWithoutInstanceofRule;
import ru.akvine.zond.rules.logical.ConstantAssertionRule;
import ru.akvine.zond.rules.logical.ConstantConditionRule;
import ru.akvine.zond.rules.logical.IndexWithoutLengthCheckRule;
import ru.akvine.zond.rules.logical.SqlChangeWithoutWhereRule;
import ru.akvine.zond.rules.logical.SqlDestructiveStatementRule;
import ru.akvine.zond.rules.performance.SqlIndexWithoutConcurrentlyRule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ложные срабатывания, найденные на библиотеках и серверах: Activiti, Hazelcast, bitcoinj, traccar
 */
class LibraryProjectsFalsePositivesTest {
    @TempDir
    Path dir;

    @Test
    void junit4MessageComesFirst() {
        String code = """
                class HDPathTest {
                    @Test
                    void primaryConstructor() {
                        assertEquals("Path not empty", 0, path.size());
                        assertEquals("constant", 1, 1);
                    }
                }
                """;

        // В JUnit 4 первым идет сообщение: проверяется path.size(), а сравнение двух литералов - находка
        assertThat(RuleTests.lines(new ConstantAssertionRule(), code)).containsExactly(5);
    }

    @Test
    void fxmlHandlerIsCalledFromMarkup() {
        Map<String, String> files = Map.of(
                "MainController.java", """
                        import javafx.event.ActionEvent;
                        public class MainController {
                            public void settingsClicked(ActionEvent event) {
                                overlay("wallet_settings.fxml");
                            }
                            public void forgotten() {
                            }
                        }
                        """,
                "Main.java", """
                        public class Main {
                            public static void main(String[] args) {
                                new MainController();
                            }
                        }
                        """);

        // onAction="#settingsClicked" в разметке FXML вызывает обработчик по имени
        assertThat(RuleTests.checkProject(new UnusedMethodRule(), files)).extracting(Violation::message)
                .singleElement().asString().contains("'MainController.forgotten'");
    }

    @Test
    void moduleApiIsOnlySuspected() {
        List<SourceFile> sources = List.of(
                RuleTests.parse(dir.resolve("core/src/main/java/Wallet.java"), """
                        public class Wallet {
                            public void addAndActivateHDChain() {
                            }
                        }
                        """),
                RuleTests.parse(dir.resolve("core/src/main/java/DbSchemaCreate.java"), """
                        public class DbSchemaCreate {
                            public static void main(String[] args) {
                                new Wallet();
                            }
                        }
                        """),
                RuleTests.parse(dir.resolve("tool/src/main/java/WalletTool.java"), """
                        public class WalletTool {
                            public static void main(String[] args) {
                                new Wallet();
                                new WalletTool();
                            }
                            public void unusedCommand() {
                            }
                        }
                        """));

        List<Violation> violations = new UnusedMethodRule().checkProject(sources);

        // Открытый метод модуля без main - API библиотеки, им могут пользоваться снаружи. Служебный main в
        // модуле, которым пользуются другие модули, его приложением не делает
        assertThat(violations).extracting(violation -> violation.file().getFileName() + ":" + violation.confidenceOrDefault())
                .containsExactlyInAnyOrder("Wallet.java:" + Confidence.SUSPICION, "WalletTool.java:" + Confidence.PROBABLE);
    }

    @Test
    void serviceLoaderCreatesRegisteredClasses() throws IOException {
        Path module = dir.resolve("extensions/avro");
        Files.createDirectories(module.resolve("src/main/resources/META-INF/services"));
        Files.writeString(module.resolve("src/main/resources/META-INF/services/com.hazelcast.SerializerHook"), """
                # Copyright (c) Hazelcast
                com.hazelcast.jet.avro.impl.AvroSerializerHooks$GenericContainerHook
                """);
        List<SourceFile> sources = List.of(
                RuleTests.parse(module.resolve("src/main/java/com/hazelcast/jet/avro/impl/AvroSerializerHooks.java"), """
                        package com.hazelcast.jet.avro.impl;
                        public final class AvroSerializerHooks {
                            public static final class GenericContainerHook {
                            }
                            public static final class ForgottenHook {
                            }
                        }
                        """),
                RuleTests.parse(dir.resolve("app/src/main/java/App.java"), """
                        public class App {
                            public static void main(String[] args) {
                                new AvroSerializerHooks();
                            }
                        }
                        """));

        assertThat(new UnusedClassRule().checkProject(sources)).extracting(Violation::message)
                .singleElement().asString().contains("'ForgottenHook'");
    }

    @Test
    void protocolFieldsBitsAndUnits() {
        String code = """
                class Gt06ProtocolDecoder {
                    Position decode(ByteBuf buf, int status) {
                        position.setValid(!BitUtil.check(status, 5));
                        buf.skipBytes(4);
                        String imei = buf.readSlice(15).toString(StandardCharsets.US_ASCII);
                        int length = buf.getUnsignedShort(buf.readerIndex() + 4);
                        position.setPower(buf.readUnsignedShort() * 0.1);
                        position.setSpeed(speed * 1.852);
                        position.setHabit(orbit.move(3));
                        return position;
                    }
                }
                """;

        // Номер бита, длина и смещение поля, множитель единицы - не магия; 1.852 и 3 - магия
        assertThat(RuleTests.lines(new MagicNumberRule(), code)).containsExactly(8, 9);
    }

    @Test
    void frameworkMessageAndSuperEquals() {
        String code = """
                class NavisetProtocolDecoder extends BaseProtocolDecoder {
                    @Override
                    protected Object decode(Channel channel, SocketAddress remoteAddress, Object msg) {
                        ByteBuf buf = (ByteBuf) msg;
                        return buf;
                    }
                    @Override
                    public boolean equals(Object o) {
                        if (!super.equals(o)) {
                            return false;
                        }
                        NavisetProtocolDecoder that = (NavisetProtocolDecoder) o;
                        return true;
                    }
                    Object convert(Object value) {
                        return (Integer) value;
                    }
                }
                """;

        // Сообщение конвейера Netty и equals после super.equals(o) - тип известен; просто Object - нет
        assertThat(RuleTests.lines(new CastWithoutInstanceofRule(), code)).containsExactly(16);
    }

    @Test
    void parsingInsideDecoderThatThrows() {
        String code = """
                class TaipProtocolDecoder {
                    @Override
                    protected Object decode(Channel channel, Object msg) throws Exception {
                        String[] values = ((String) msg).split(",");
                        int speed = Integer.parseInt(values[2]);
                        return decodeAlarm(values);
                    }
                    private Position decodeAlarm(String[] values) {
                        return new Position(Integer.parseInt(values[3]), values[4]);
                    }
                    public int parseCount(String text) {
                        String[] parts = text.split(":");
                        return Integer.parseInt(parts[1]);
                    }
                }
                """;

        // decode объявлен throws Exception, decodeAlarm вызывает только он: неверный ввод уходит фреймворку
        assertThat(RuleTests.lines(new UnhandledNumberFormatRule(), code)).containsExactly(13);
        assertThat(RuleTests.lines(new IndexWithoutLengthCheckRule(), code)).containsExactly(13);
    }

    @Test
    void consoleProgramsAndExamples() {
        String tool = """
                @Command(name = "wallet-tool")
                class WalletTool {
                    void dump() {
                        System.out.println(wallet);
                    }
                }
                """;
        String service = """
                class WalletService {
                    void dump() {
                        System.out.println(wallet);
                    }
                }
                """;
        String interactive = """
                class ConsoleApp {
                    public static void main(String[] args) {
                        new ConsoleApp().start();
                    }
                    void start() {
                        LineReader lineReader = new DefaultLineReader();
                        handleCommand(lineReader.readLine());
                    }
                    void handleCommand(String command) {
                        System.out.println("unknown command: " + command);
                    }
                }
                """;
        String forgottenMain = """
                class ApiAuthFilter {
                    void doFilter() {
                        System.out.println(request);
                    }
                    public static void main(String[] args) {
                        System.out.println("signature:" + md5(args[0]));
                        printUsage();
                    }
                    private static void printUsage() {
                        System.out.println("usage: sign <key>");
                    }
                }
                """;

        assertThat(RuleTests.lines(new ConsoleOutputRule(), tool)).isEmpty();
        assertThat(RuleTests.lines(new ConsoleOutputRule(), interactive)).isEmpty();
        assertThat(RuleTests.check(new ConsoleOutputRule(),
                Path.of("examples/src/main/java/ForwardingService.java"), service)).isEmpty();
        assertThat(RuleTests.lines(new ConsoleOutputRule(), service)).containsExactly(3);
        // main, забытый в рабочем классе, прячет только свой вывод и вывод своих помощников
        assertThat(RuleTests.lines(new ConsoleOutputRule(), forgottenMain)).containsExactly(3);
    }

    @Test
    void dropScriptsAndMysqlComments() {
        List<String> destructive = new ProjectFixture(dir)
                .write("src/main/resources/org/activiti/db/drop/activiti.mysql.drop.engine.sql",
                        "drop table if exists ACT_RU_JOB;\n")
                .write("src/main/resources/db/migration/V2__drop_legacy.sql", "DROP TABLE legacy;\n")
                .lines(new SqlDestructiveStatementRule());
        List<String> withoutWhere = new ProjectFixture(dir)
                .write("src/main/resources/org/activiti/db/upgrade/activiti.mysql.upgradestep.514.to.515.engine.sql", """
                        # DEPLOY_TIME_ in ACT_RE_DEPLOYMENT

                        ALTER TABLE ACT_RE_DEPLOYMENT ADD DEPLOY_TIME_TEMP_ timestamp(3);
                        UPDATE ACT_RE_DEPLOYMENT SET DEPLOY_TIME_TEMP_ = DEPLOY_TIME_;
                        UPDATE ACT_RU_TASK SET PRIORITY_ = 0;
                        """)
                .lines(new SqlChangeWithoutWhereRule());

        // Скрипт удаления схемы - не миграция; Flyway V2__drop_legacy.sql - миграция
        assertThat(destructive).containsExactly("V2__drop_legacy.sql:1");
        // Комментарий MySQL не склеивается с ALTER, и заполнение новой колонки узнается
        assertThat(withoutWhere).containsExactly("activiti.mysql.upgradestep.514.to.515.engine.sql:5");
    }

    @Test
    void indexScriptForAnotherDatabase() {
        String index = "CREATE INDEX ACT_IDX_HI_TASK_INST ON ACT_HI_TASKINST(PROC_INST_ID_);\n";
        List<String> lines = new ProjectFixture(dir)
                .write("src/main/resources/org/activiti/db/upgrade/activiti.mysql.upgradestep.710.to.711.engine.sql", index)
                .write("src/main/resources/org/activiti/db/upgrade/activiti.db2.upgradestep.710.to.711.engine.sql", index)
                .write("src/main/resources/org/activiti/db/upgrade/activiti.postgres.upgradestep.710.to.711.engine.sql", index)
                .lines(new SqlIndexWithoutConcurrentlyRule());

        // CONCURRENTLY есть только в PostgreSQL
        assertThat(lines).containsExactly("activiti.postgres.upgradestep.710.to.711.engine.sql:1");
    }

    @Test
    void handlerCallbacksAssertAndGeneratedSuites() {
        String handler = """
                class ClientCompatibilityTest {
                    @Test
                    public void clusterVersionEvent() {
                        ClientAddClusterViewListenerCodecHandler handler = new ClientAddClusterViewListenerCodecHandler();
                        handler.handle(fromFile);
                    }
                    private static class ClientAddClusterViewListenerCodecHandler extends AbstractEventHandler {
                        @Override
                        public void handleMembersViewEvent(int version) {
                            assertTrue(isEqual(anInt, version));
                        }
                    }
                }
                """;
        String production = """
                class PeerTest {
                    @Test
                    public void shutdown() {
                        peer.close();
                    }
                    @Test
                    @Override
                    public void whenDrainerFails() {
                        // FIXME
                    }
                    private Peer peer;
                }
                class Peer extends PeerSocketHandler {
                    @Override
                    public void connectionClosed() {
                        checkState(connected);
                    }
                }
                """;
        StringBuilder generated = new StringBuilder("class ClientCompatibilityNullTest {\n");
        StringBuilder handWritten = new StringBuilder("class UserServiceTest {\n");
        for (int index = 0; index < 12; index++) {
            generated.append("    @Test\n    public void test_Codec").append(index)
                    .append("_decodeResponse() {\n        int fileClientMessageIndex = ").append(index).append(";\n    }\n");
            handWritten.append("    @Test\n    public void saveRole").append(index).append("() {\n        service.save();\n    }\n");
        }
        for (int index = 0; index < 200; index++) {
            generated.append("    @Test\n    public void test_Codec").append(index)
                    .append("_encodeRequest() {\n        assertTrue(isEqual(fromFile, encoded));\n    }\n");
        }
        generated.append("}\n");
        handWritten.append("}\n");

        // handle() вызывает переопределенный обработчик с проверкой; двенадцать пустых тестов в наборе генератора
        // на двести с лишним тестов - одна находка, в обычном классе - по одной на каждый
        assertThat(RuleTests.lines(new TestWithoutAssertionRule(), handler)).isEmpty();
        // checkState в рабочем классе - проверка входа, а не теста
        assertThat(RuleTests.lines(new TestWithoutAssertionRule(), production)).containsExactly(2);
        assertThat(RuleTests.check(new TestWithoutAssertionRule(), generated.toString())).extracting(Violation::message)
                .singleElement().asString().startsWith("12 тестов в этом файле ничего не проверяют");
        assertThat(RuleTests.lines(new TestWithoutAssertionRule(), handWritten.toString())).hasSize(12);
    }

    @Test
    void unknownCallKeepsIntRange() {
        String code = """
                class Jt600ProtocolDecoder {
                    int decode(ByteBuf buf, boolean longFormat, boolean shortFormat) {
                        int protocolVersion = 0;
                        if (longFormat) {
                            protocolVersion = buf.readUnsignedByte();
                        }
                        if (protocolVersion == 0x17) {
                            return 1;
                        }
                        int type = 0;
                        if (shortFormat) {
                            type = 5;
                        }
                        if (type == 23) {
                            return 2;
                        }
                        return 0;
                    }
                }
                """;

        // int, которому присвоили неизвестное число, - любое int, а не 0..1, как у boolean
        assertThat(RuleTests.lines(new ConstantConditionRule(), code)).containsExactly(14);
    }
}
