package com.testgen.generator;

public record GeneratedTest(
        String fileName,
        String packageName,
        String content,
        GeneratedTestType type
) {
    public enum GeneratedTestType { TEST_CLASS, TEST_DATA }
}
