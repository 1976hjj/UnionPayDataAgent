package com.company.paymentanalysis.agent;

/**
 * A user-approved transition on the active skill. EXECUTE is reserved for the
 * later attribution-execution phase; phase two implements MESSAGE and CONFIRM.
 */
public enum AgentAction {
    MESSAGE,
    CONFIRM,
    EXECUTE
}
