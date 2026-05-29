package com.testgen.generator;

public enum NamingConvention {

    TEST_METHOD_SCENARIO("test_method_scenario"),
    SHOULD_WHEN("methodName_shouldDoX_whenY"),
    GIVEN_WHEN_THEN("given_when_then");

    private final String displayName;

    NamingConvention(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() { return displayName; }

    public String unitTestMethod(String methodName, String scenario) {
        return switch (this) {
            case TEST_METHOD_SCENARIO -> "test_%s_%s".formatted(methodName, scenario);
            case SHOULD_WHEN          -> "%s_should%s".formatted(methodName, cap(scenario));
            case GIVEN_WHEN_THEN      -> "given_%s_when_%s_then_success".formatted(scenario, methodName);
        };
    }

    public String exceptionTestMethod(String methodName, String exceptionType) {
        String ex = exceptionType.replace("Exception", "").replace("Error", "");
        return switch (this) {
            case TEST_METHOD_SCENARIO -> "test_%s_throws%s".formatted(methodName, ex);
            case SHOULD_WHEN          -> "%s_shouldThrow%s".formatted(methodName, ex);
            case GIVEN_WHEN_THEN      -> "given_invalid_when_%s_then_throws%s".formatted(methodName, ex);
        };
    }

    @Override
    public String toString() { return displayName; }

    private static String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
