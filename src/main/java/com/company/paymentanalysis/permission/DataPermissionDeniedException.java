package com.company.paymentanalysis.permission;

/** Raised before SmartBI is called when a login has no complete required data scope. */
public class DataPermissionDeniedException extends IllegalArgumentException {

    public DataPermissionDeniedException(String message) {
        super(message);
    }
}
