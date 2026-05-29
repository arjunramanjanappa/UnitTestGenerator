package com.testgen.generator.strategy;

import com.testgen.generator.GeneratedTest;
import com.testgen.generator.NamingConvention;
import com.testgen.parser.ClassMetadata;

import java.util.List;

public interface TestStrategy {
    List<GeneratedTest> generate(ClassMetadata metadata, NamingConvention convention);
}
