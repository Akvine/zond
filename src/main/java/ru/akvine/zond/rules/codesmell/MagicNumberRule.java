package ru.akvine.zond.rules.codesmell;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LiteralStringValueExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithArguments;
import com.github.javaparser.ast.stmt.SwitchEntry;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.LocalTypes;
import ru.akvine.zond.rules.support.Nodes;
import ru.akvine.zond.rules.support.TestClasses;

import java.util.ArrayList;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class MagicNumberRule extends AbstractRule {
    private static final String HASH_CODE = "hashCode";
    private static final int MIN_TABLE_SIZE = 3;
    private static final int MIN_VARIANTS = 3;
    private static final Pattern SMALL_NUMBER = Pattern.compile("\\d{1,4}");
    private static final String GROUP = "group";
    private static final Set<String> GROUP_BOUNDS = Set.of("start", "end");
    private static final Set<String> MATCH_TYPES = Set.of("Matcher", "MatchResult");
    // Apache POI: row.createCell(3), row.getCell(3)
    private static final Set<String> CELL_METHODS = Set.of("createCell", "getCell");
    private static final Set<String> JDBC_TYPES = Set.of(
            "PreparedStatement", "CallableStatement", "ResultSet", "SqlRowSet", "ResultSetMetaData");
    // ps, stmt, statement, preparedStatement, rs, resultSet, rowSet, metaData - когда тип переменной не виден
    private static final Pattern JDBC_NAME = Pattern.compile(
            "(?i)(ps|pstmt|stmt|cs|rs|rsmd|\\w*statement|\\w*resultset|\\w*rowset|\\w*metadata)");
    private static final Pattern JDBC_SETTER = Pattern.compile("(set|update|registerOut)[A-Z]\\w*");
    private static final Pattern JDBC_GETTER = Pattern.compile("(get|is)[A-Z]\\w*");
    private static final String POW = "pow";
    private static final Pattern DECIMAL_BASE = Pattern.compile("10(\\.0)?[dDfF]?");

    private static final Pattern UNIT_CONSTANT = Pattern.compile("(TimeUnit|ChronoUnit|DataUnit)\\.[A-Z_]+");
    private static final Pattern UNIT_FACTORY = Pattern.compile("^(Duration|Period|DataSize)\\.of[A-Z]\\w*\\(");

    // BitUtil.check(status, 5), bits.get(3): номер бита
    private static final Pattern BIT_HELPER = Pattern.compile("^([Bb]it.*|.*Bits?)$");
    // buf.skipBytes(4), buf.readSlice(15), buf.getUnsignedShort(buf.readerIndex() + 4): длина и смещение поля в
    // двоичном протоколе - их задает описание формата, а не автор кода
    private static final Set<String> BUFFER_METHODS = Set.of(
            "skipBytes", "readSlice", "readRetainedSlice", "readCharSequence", "writeZero", "ensureWritable",
            "readerIndex", "writerIndex", "bytesBefore", "getUnsignedByte", "getUnsignedShort", "getUnsignedShortLE",
            "getUnsignedMedium", "getUnsignedMediumLE", "getUnsignedInt", "getUnsignedIntLE", "getShortLE",
            "getIntLE", "getLongLE", "getMedium", "retainedSlice");
    // value * 0.1, millis / 1000.0, bytes / (1024 * 1024): множитель единицы измерения
    private static final Set<Double> UNIT_FACTORS = Set.of(
            10.0, 100.0, 1000.0, 10000.0, 100000.0, 1000000.0, 1.0E9, 0.1, 0.01, 0.001, 0.0001, 0.00001, 0.000001,
            24.0, 60.0, 3600.0, 86400.0, 1024.0, 1048576.0);

    // Числа, смысл которых понятен без имени
    private static final Set<Double> ALLOWED = Set.of(0.0, 1.0, 2.0);

    private static final Set<String> RADIXES = Set.of("2", "8", "10", "16", "32", "36");
    private static final Set<String> RADIX_METHODS = Set.of(
            "parseInt", "parseLong", "parseUnsignedInt", "parseUnsignedLong", "valueOf", "toString",
            "toUnsignedString", "digit", "forDigit", "parseShort", "parseByte");
    private static final String BIG_INTEGER = "BigInteger";
    private static final String TO_STRING = "toString";
    private static final Set<BinaryExpr.Operator> SHIFTS = Set.of(BinaryExpr.Operator.LEFT_SHIFT,
            BinaryExpr.Operator.SIGNED_RIGHT_SHIFT, BinaryExpr.Operator.UNSIGNED_RIGHT_SHIFT);

    @Override
    public String code() {
        return RuleCodes.MAGIC_NUMBER_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет числа без имени (магические числа) в выражениях";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        List<LiteralStringValueExpr> literals = new ArrayList<>();
        literals.addAll(sourceFile.unit().findAll(IntegerLiteralExpr.class));
        literals.addAll(sourceFile.unit().findAll(LongLiteralExpr.class));
        literals.addAll(sourceFile.unit().findAll(DoubleLiteralExpr.class));

        return literals.stream()
                .filter(literal -> !isAllowed(literal.getValue()))
                .filter(literal -> !isNamed(literal))
                .filter(literal -> !isInIgnoredContext(literal))
                .map(literal -> violation(sourceFile, literal,
                        "Магическое число " + literal.getValue() + ": без имени непонятно, что оно означает;"
                                + " вынесите его в константу"))
                .sorted((left, right) -> Integer.compare(left.line(), right.line()))
                .toList();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.INFO;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.CODE_SMELL;
    }

    private boolean isAllowed(String literal) {
        try {
            return ALLOWED.contains(Double.parseDouble(literal.replace("_", "").replaceAll("[lLfFdD]$", "")));
        } catch (NumberFormatException exception) {
            // 0xFF, 0b1010: битовые маски и флаги
            return true;
        }
    }

    // int timeout = 5000; - у числа уже есть имя, это имя переменной
    private boolean isNamed(Node literal) {
        Node parent = literal.getParentNode().orElse(null);
        while (parent instanceof UnaryExpr || parent instanceof EnclosedExpr) {
            parent = parent.getParentNode().orElse(null);
        }
        return parent instanceof VariableDeclarator;
    }

    // Константы и поля, аннотации, enum, hashCode и тесты: там числа либо уже названы, либо уместны как есть.
    // Таблица данных - {1, 2, 3} или GBKFreq[54][125] = 493 - это значения, а не магия: имя у таблицы
    private boolean isInIgnoredContext(Node literal) {
        Node current = literal.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof FieldDeclaration
                    || current instanceof AnnotationExpr
                    || current instanceof AnnotationMemberDeclaration
                    || current instanceof EnumConstantDeclaration
                    || current instanceof ArrayInitializerExpr table && isDataTable(table)
                    || current instanceof AssignExpr assignment && isTableEntry(assignment)
                    || (current instanceof MethodDeclaration method && HASH_CODE.equals(method.getNameAsString()))) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return TestClasses.isInside(literal) || hasUnit(literal) || isUnitFactor(literal) || isPosition(literal);
    }

    // Число называет место, а не величину: номер бита, смещение поля, основание, сдвиг, группа шаблона, вариант
    private boolean isPosition(Node literal) {
        return isBitPosition(literal) || isFieldLayout(literal) || isRadix(literal) || isShiftDistance(literal)
                || isGroupNumber(literal) || isVariantNumber(literal) || isOrdinal(literal) || isDecimalBase(literal);
    }

    /**
     * statement.setString(3, name), resultSet.getLong(4), row.createCell(5): номер параметра запроса, колонки
     * выборки или ячейки строки - место в запросе или таблице, которое задано рядом, а не величина
     */
    private boolean isOrdinal(Node literal) {
        if (!(literal.getParentNode().orElse(null) instanceof MethodCallExpr call) || call.getScope().isEmpty()
                || call.getArguments().isEmpty() || call.getArgument(0) != literal) {
            return false;
        }
        String method = call.getNameAsString();
        if (CELL_METHODS.contains(method)) {
            return call.getArguments().size() == 1;
        }
        Expression scope = call.getScope().get();
        boolean jdbc = LocalTypes.typeOf(scope)
                .map(JDBC_TYPES::contains)
                .orElseGet(() -> JDBC_NAME.matcher(scope.toString()).matches());
        boolean parameter = JDBC_SETTER.matcher(method).matches() && call.getArguments().size() >= 2;
        boolean column = JDBC_GETTER.matcher(method).matches() && call.getArguments().size() == 1;
        return jdbc && (parameter || column);
    }

    // Math.pow(10, digits): десять - основание десятичной записи, как основание в Integer.parseInt(text, 16)
    private boolean isDecimalBase(Node literal) {
        return literal instanceof LiteralStringValueExpr number && DECIMAL_BASE.matcher(number.getValue()).matches()
                && literal.getParentNode().orElse(null) instanceof MethodCallExpr call
                && POW.equals(call.getNameAsString()) && call.getArguments().size() == 2 && call.getArgument(0) == literal;
    }

    // matcher.group(3), matcher.start(2): номер группы задает сам шаблон - это место в нем, а не величина
    private boolean isGroupNumber(Node literal) {
        Node parent = literal.getParentNode().orElse(null);
        // negative.group(reversed ? 3 : 2)
        while (parent instanceof EnclosedExpr || parent instanceof ConditionalExpr) {
            parent = parent.getParentNode().orElse(null);
        }
        if (!(parent instanceof MethodCallExpr call) || call.getArguments().size() != 1 || call.getScope().isEmpty()) {
            return false;
        }
        return GROUP.equals(call.getNameAsString())
                || GROUP_BOUNDS.contains(call.getNameAsString())
                && LocalTypes.typeOf(call.getScope().get()).filter(MATCH_TYPES::contains).isPresent();
    }

    /**
     * switch (choice) { case 1 -> scan(); case 2 -> report(); case 3 -> settings(); }: метки идут подряд от нуля
     * или единицы - это номера вариантов (пункты меню, шаги), и что значит каждый, сказано в его ветке
     */
    private boolean isVariantNumber(Node literal) {
        if (!(literal.getParentNode().orElse(null) instanceof SwitchEntry entry)) {
            return false;
        }
        List<Expression> labels = entry.getParentNode().stream()
                .flatMap(owner -> owner.getChildNodes().stream())
                .filter(child -> child instanceof SwitchEntry)
                .flatMap(child -> ((SwitchEntry) child).getLabels().stream())
                .toList();
        if (labels.size() < MIN_VARIANTS || !labels.stream().allMatch(this::isSmallNumber)) {
            return false;
        }
        // Метки в одном switch не повторяются: подряд - значит, от первой до последней нет пропусков
        IntSummaryStatistics range = labels.stream()
                .mapToInt(label -> Integer.parseInt(label.asIntegerLiteralExpr().getValue()))
                .summaryStatistics();
        return range.getMin() <= 1 && range.getMax() - range.getMin() == labels.size() - 1;
    }

    private boolean isSmallNumber(Expression label) {
        return label.isIntegerLiteralExpr() && SMALL_NUMBER.matcher(label.asIntegerLiteralExpr().getValue()).matches();
    }

    // Integer.parseInt(hex, 16), Character.digit(c, 16), new BigInteger(text, 2): основание системы счисления
    private boolean isRadix(Node literal) {
        if (!(literal instanceof IntegerLiteralExpr number) || !RADIXES.contains(number.getValue())) {
            return false;
        }
        Node parent = literal.getParentNode().orElse(null);
        if (parent instanceof MethodCallExpr call && RADIX_METHODS.contains(call.getNameAsString())) {
            // bigInteger.toString(16); Integer.valueOf(16) - это уже само число, а не основание
            return call.getArguments().size() > 1 && call.getArgument(call.getArguments().size() - 1) == literal
                    || call.getArguments().size() == 1 && TO_STRING.equals(call.getNameAsString())
                    && call.getScope().isPresent();
        }
        return parent instanceof ObjectCreationExpr creation && BIG_INTEGER.equals(creation.getType().getNameAsString())
                && creation.getArguments().size() == 2 && creation.getArgument(1) == literal;
    }

    // value >> 16, b3 >>> 6, 1 << 10: на сколько разрядов сдвинуть - часть разбора двоичного формата
    private boolean isShiftDistance(Node literal) {
        Node parent = literal.getParentNode().orElse(null);
        return parent instanceof BinaryExpr shift && shift.getRight() == literal && SHIFTS.contains(shift.getOperator());
    }

    // table[54][125] = 493: и номера ячейки, и значение записаны числами - строка таблицы данных
    // {2, 4, 10, 3, 5, 9} - веса и таблицы. Пара чисел {300, 700} - это уже коды, которым нужно имя
    private boolean isDataTable(ArrayInitializerExpr table) {
        return table.getValues().size() >= MIN_TABLE_SIZE;
    }

    private boolean isTableEntry(AssignExpr assignment) {
        Expression target = assignment.getTarget();
        if (!target.isArrayAccessExpr() || !isNumber(assignment.getValue())) {
            return false;
        }
        while (target.isArrayAccessExpr()) {
            if (!isNumber(target.asArrayAccessExpr().getIndex())) {
                return false;
            }
            target = target.asArrayAccessExpr().getName();
        }
        return true;
    }

    private boolean isNumber(Expression expression) {
        Expression value = Nodes.unwrap(expression);
        if (value.isUnaryExpr()) {
            value = Nodes.unwrap(value.asUnaryExpr().getExpression());
        }
        return value.isIntegerLiteralExpr() || value.isLongLiteralExpr() || value.isDoubleLiteralExpr();
    }

    // Duration.ofSeconds(30), poll(5, TimeUnit.SECONDS): смысл числа назван единицей измерения рядом
    private boolean isBitPosition(Node literal) {
        return argumentOf(literal)
                .flatMap(MethodCallExpr::getScope)
                .filter(scope -> BIT_HELPER.matcher(scope.toString()).matches())
                .isPresent();
    }

    private boolean isFieldLayout(Node literal) {
        return argumentOf(literal).filter(call -> BUFFER_METHODS.contains(call.getNameAsString())).isPresent();
    }

    // Вызов, аргументом которого стоит число - само по себе либо в простой арифметике: buf.readerIndex() + 4
    private Optional<MethodCallExpr> argumentOf(Node literal) {
        Node parent = literal.getParentNode().orElse(null);
        while (parent instanceof UnaryExpr || parent instanceof EnclosedExpr || parent instanceof BinaryExpr) {
            parent = parent.getParentNode().orElse(null);
        }
        return parent instanceof MethodCallExpr call && call.getArguments().stream()
                .anyMatch(argument -> argument.isAncestorOf(literal) || argument == literal)
                ? Optional.of(call) : Optional.empty();
    }

    private boolean isUnitFactor(Node literal) {
        if (!(literal instanceof LiteralStringValueExpr value)) {
            return false;
        }
        Node parent = literal.getParentNode().orElse(null);
        while (parent instanceof EnclosedExpr) {
            parent = parent.getParentNode().orElse(null);
        }
        boolean scaling = parent instanceof BinaryExpr binary
                && (binary.getOperator() == BinaryExpr.Operator.MULTIPLY || binary.getOperator() == BinaryExpr.Operator.DIVIDE);
        try {
            return scaling && UNIT_FACTORS.contains(
                    Double.parseDouble(value.getValue().replace("_", "").replaceAll("[lLfFdD]$", "")));
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean hasUnit(Node literal) {
        Node parent = literal.getParentNode().orElse(null);
        while (parent instanceof UnaryExpr || parent instanceof EnclosedExpr) {
            parent = parent.getParentNode().orElse(null);
        }
        if (parent instanceof MethodCallExpr call && UNIT_FACTORY.matcher(call.toString()).find()) {
            return true;
        }
        return parent instanceof NodeWithArguments<?> call && call.getArguments().stream()
                .anyMatch(argument -> UNIT_CONSTANT.matcher(argument.toString()).matches());
    }
}
