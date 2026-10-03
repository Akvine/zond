package ru.akvine.zond.rules;

import lombok.experimental.UtilityClass;

@UtilityClass
public class RuleCodes {

    public final static String CHECK_TRANSACTION_ON_PRIVATE_METHOD_RULE_CODE = "jr:1";
    public final static String CHECK_TRANSACTIONAL_SELF_INVOCATION_RULE_CODE = "jr:2";
    public final static String CHECK_TRANSACTIONAL_ROLLBACK_FOR_CHECKED_EXCEPTION_RULE_CODE = "jr:3";
}
