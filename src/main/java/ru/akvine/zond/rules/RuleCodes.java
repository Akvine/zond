package ru.akvine.zond.rules;

import lombok.experimental.UtilityClass;

@UtilityClass
public class RuleCodes {

    public final static String CHECK_TRANSACTION_ON_PRIVATE_METHOD_RULE_CODE = "jr:1";
    public final static String CHECK_TRANSACTIONAL_SELF_INVOCATION_RULE_CODE = "jr:2";
    public final static String CHECK_TRANSACTIONAL_ROLLBACK_FOR_CHECKED_EXCEPTION_RULE_CODE = "jr:3";
    public final static String CHECK_AUTOWIRED_ON_STATIC_FIELD_RULE_CODE = "jr:4";
    public final static String CHECK_TRANSACTIONAL_ON_CONTROLLER_RULE_CODE = "jr:5";
    public final static String CHECK_FIELD_INJECTION_RULE_CODE = "jr:6";
    public final static String CHECK_MUTABLE_STATE_IN_SINGLETON_BEAN_RULE_CODE = "jr:7";
}
