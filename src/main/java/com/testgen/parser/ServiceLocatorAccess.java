package com.testgen.parser;

import java.util.List;

/**
 * Represents a service-locator pattern detected in a method body:
 *
 *   TPIBFTPayeeRepo repo = (TPIBFTPayeeRepo) makeDAO(TPIBFtPayeeRepo.BEAN_ID);
 *   List<TPIBFTPayee> list = repo.getPayeeDetails(ichkey, acNo);
 *
 * repoType      = "TPIBFTPayeeRepo"    — the @Repository interface obtained
 * locatorMethod = "makeDAO"            — the service-locator method called
 * fieldName     = "tpibFTPayeeRepo"    — camelCase mock field name
 * repoCalls     = [{methodName="getPayeeDetails", params=[...]}]
 */
public record ServiceLocatorAccess(
        String repoType,            // simple class name of the @Repository
        String locatorMethod,       // method used to fetch it (e.g. makeDAO)
        String fieldName,           // camelCase mock field name
        List<RepoCall> repoCalls    // methods called on the repo variable in this context
) {
    /** A single method call detected on the service-locator variable. */
    public record RepoCall(
            String methodName,
            List<MethodMetadata.ParameterMetadata> params, // resolved from interface source
            String returnType                              // resolved from interface source
    ) {
        public boolean returnsCollection() {
            return returnType != null && (returnType.startsWith("List")
                    || returnType.startsWith("Collection")
                    || returnType.startsWith("Set")
                    || returnType.startsWith("Optional"));
        }
    }

    /** Derives a camelCase field name from the repo simple class name. */
    public static String toFieldName(String repoType) {
        if (repoType == null || repoType.isEmpty()) return "repoMock";
        return Character.toLowerCase(repoType.charAt(0)) + repoType.substring(1);
    }
}
