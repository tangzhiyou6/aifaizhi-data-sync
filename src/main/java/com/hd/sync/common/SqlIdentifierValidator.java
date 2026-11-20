package com.hd.sync.common;

/**
 * SQL 标识符校验：schema / table 名会拼接进 SQL，必须先校验。
 */
public final class SqlIdentifierValidator {

    private static final String PATTERN = "^[A-Za-z_][A-Za-z0-9_$]*$";

    private SqlIdentifierValidator() {
    }

    /**
     * 校验并返回标识符，不合法直接抛异常。
     */
    public static String validate(String identifier, String label) {
        if (identifier == null || !identifier.matches(PATTERN)) {
            throw new IllegalArgumentException("非法的 SQL 标识符(" + label + "): " + identifier);
        }
        return identifier;
    }
}
