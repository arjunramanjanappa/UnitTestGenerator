package com.testgen.parser;

/**
 * Represents a conditional branch detected in a method body.
 * Used to generate named TestData scenario methods and targeted branch tests.
 *
 * Example: if (vo.getAmount() == null) { throw ... }
 *   → paramName    = "vo"
 *   → paramType    = "MSBaseVO"
 *   → fieldName    = "amount"
 *   → setterName   = "setAmount"
 *   → type         = NULL_CHECK
 *   → trueLabel    = "amountNull"     (amount=null  → condition TRUE  → exception path)
 *   → falseLabel   = "amountPresent"  (amount=1L    → condition FALSE → normal path)
 *   → trueSetExpr  = "null"
 *   → falseSetExpr = "1L"
 */
public record ConditionScenario(
        String methodName,   // source method the condition lives in
        String paramName,    // parameter variable name  (e.g. "vo")
        String paramType,    // simple type name         (e.g. "MSBaseVO")
        String fieldName,    // field derived from getter (e.g. "amount")
        String setterName,   // JavaBeans setter          (e.g. "setAmount")
        ConditionType type,
        String trueLabel,    // short label for the TRUE  scenario method name
        String falseLabel,   // short label for the FALSE scenario method name
        String trueSetExpr,  // Java literal to set for TRUE  (e.g. "null", "true", "\"CONFIRMED\"")
        String falseSetExpr  // Java literal to set for FALSE (e.g. "1L",  "false", "\"OTHER\"")
) {
    public enum ConditionType {
        NULL_CHECK,     // param.getField() == null / != null
        BOOLEAN_CHECK,  // param.isField()
        EQUALS_CHECK,   // param.getField().equals("value")
        NUMERIC_CHECK   // param.getField() > 0 / < value / etc.
    }

    /** Scenario method name for the TRUE path in the owner class's TestData */
    public String trueMethodName() {
        return "build_" + methodName + "_" + trueLabel;
    }

    /** Scenario method name for the FALSE path */
    public String falseMethodName() {
        return "build_" + methodName + "_" + falseLabel;
    }
}
