package com.testgen.parser;

/**
 * Represents a service-locator pattern detected in a method body:
 *
 *   TPIBFTPayeeRepo repo = (TPIBFTPayeeRepo) makeDAO(TPIBFtPayeeRepo.BEAN_ID);
 *
 * repoType      = "TPIBFTPayeeRepo"    — the @Repository type obtained
 * locatorMethod = "makeDAO"            — the service-locator method called
 * fieldName     = "tpibFTPayeeRepo"    — suggested mock field name (camelCase)
 */
public record ServiceLocatorAccess(
        String repoType,       // simple class name of the @Repository
        String locatorMethod,  // method name used to fetch it (e.g. makeDAO)
        String fieldName       // camelCase mock field name derived from repoType
) {
    /** Derives a camelCase field name from the repo simple class name. */
    public static String toFieldName(String repoType) {
        if (repoType == null || repoType.isEmpty()) return "repoMock";
        return Character.toLowerCase(repoType.charAt(0)) + repoType.substring(1);
    }
}
