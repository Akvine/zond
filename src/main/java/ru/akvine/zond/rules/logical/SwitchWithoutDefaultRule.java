package ru.akvine.zond.rules.logical;

import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.resolution.declarations.ResolvedEnumConstantDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import org.springframework.stereotype.Component;
import ru.akvine.zond.enums.ErrorLevel;
import ru.akvine.zond.enums.ErrorType;
import ru.akvine.zond.models.SourceFile;
import ru.akvine.zond.models.Violation;
import ru.akvine.zond.rules.AbstractRule;
import ru.akvine.zond.rules.RuleCodes;
import ru.akvine.zond.rules.support.Types;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class SwitchWithoutDefaultRule extends AbstractRule {

    @Override
    public String code() {
        return RuleCodes.SWITCH_WITHOUT_DEFAULT_RULE_CODE;
    }

    @Override
    public String description() {
        return "Сканирует код и ищет оператор switch без ветки default";
    }

    @Override
    public List<Violation> check(SourceFile sourceFile) {
        // switch-выражение не трогаем: компилятор сам требует от него полноты
        return sourceFile.unit().findAll(SwitchStmt.class).stream()
                .filter(switchStmt -> switchStmt.getEntries().stream().noneMatch(entry -> entry.getLabels().isEmpty() || entry.isDefault()))
                .filter(switchStmt -> !coversWholeEnum(switchStmt) && !hasFallbackAfter(switchStmt))
                .map(switchStmt -> violation(sourceFile, switchStmt,
                        "switch по '" + switchStmt.getSelector() + "' без default: значение, для которого нет"
                                + " ветки, будет молча пропущено; добавьте default, хотя бы с исключением"))
                .toList();
    }

    // Ветка есть у каждой константы перечисления: значения без ветки сегодня нет. Перечисление ищется по типу
    // выражения, а когда тип неизвестен - среди перечислений этого же файла
    private boolean coversWholeEnum(SwitchStmt switchStmt) {
        Set<String> labels = switchStmt.getEntries().stream()
                .flatMap(entry -> entry.getLabels().stream())
                .map(label -> label.isFieldAccessExpr() ? label.asFieldAccessExpr().getNameAsString() : label.toString())
                .collect(Collectors.toSet());
        Optional<Set<String>> constants = constantsOf(switchStmt.getSelector());
        if (constants.isPresent()) {
            return labels.containsAll(constants.get());
        }
        return switchStmt.findCompilationUnit().stream()
                .flatMap(unit -> unit.findAll(EnumDeclaration.class).stream())
                .map(this::names)
                .anyMatch(names -> !names.isEmpty() && names.equals(labels));
    }

    private Optional<Set<String>> constantsOf(Expression selector) {
        Optional<ResolvedType> type = Types.resolve(selector);
        Optional<TypeDeclaration<?>> declared = type.flatMap(Types::projectType);
        if (declared.isPresent()) {
            return declared.filter(TypeDeclaration::isEnumDeclaration).map(found -> names(found.asEnumDeclaration()));
        }
        try {
            return type.filter(ResolvedType::isReferenceType)
                    .flatMap(found -> found.asReferenceType().getTypeDeclaration())
                    .filter(ResolvedReferenceTypeDeclaration::isEnum)
                    .map(found -> found.asEnum().getEnumConstants().stream()
                            .map(ResolvedEnumConstantDeclaration::getName)
                            .collect(Collectors.toSet()));
        } catch (RuntimeException exception) {
            // Перечисление из библиотеки, которой нет среди библиотек проекта: его константы неизвестны
            return Optional.empty();
        }
    }

    private Set<String> names(EnumDeclaration declaration) {
        return declaration.getEntries().stream().map(EnumConstantDeclaration::getNameAsString).collect(Collectors.toSet());
    }

    // switch (type) { case A: return 1; case B: return 2; } throw new IllegalArgumentException(...):
    // каждая ветка сама выходит из метода, а значение без ветки доходит до оператора после switch -
    // он и есть ветка по умолчанию
    private boolean hasFallbackAfter(SwitchStmt switchStmt) {
        boolean everyBranchExits = switchStmt.getEntries().stream()
                .filter(entry -> !entry.getStatements().isEmpty())
                .allMatch(entry -> exits(entry.getStatements().get(entry.getStatements().size() - 1)));
        if (!everyBranchExits || !(switchStmt.getParentNode().orElse(null) instanceof BlockStmt block)) {
            return false;
        }
        int position = block.getStatements().indexOf(switchStmt);
        return position >= 0 && position + 1 < block.getStatements().size()
                && exits(block.getStatements().get(position + 1));
    }

    private boolean exits(Statement statement) {
        if (statement.isBlockStmt()) {
            List<Statement> statements = statement.asBlockStmt().getStatements();
            return !statements.isEmpty() && exits(statements.get(statements.size() - 1));
        }
        return statement.isReturnStmt() || statement.isThrowStmt();
    }

    @Override
    public ErrorLevel errorLevel() {
        return ErrorLevel.MINOR;
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.LOGICAL;
    }
}
