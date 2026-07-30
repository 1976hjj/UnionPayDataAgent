package com.company.paymentanalysis.handler;

import com.company.paymentanalysis.analysis.IntentType;

public class UnsupportedIntentException extends RuntimeException {

    private final IntentType intent;

    public UnsupportedIntentException(IntentType intent) {
        super("暂不支持该意图：" + intent);
        this.intent = intent;
    }

    public IntentType intent() {
        return intent;
    }
}
