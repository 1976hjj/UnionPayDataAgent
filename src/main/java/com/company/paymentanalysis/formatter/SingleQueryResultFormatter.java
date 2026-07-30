package com.company.paymentanalysis.formatter;

import com.company.paymentanalysis.analysis.IntentType;
import org.springframework.stereotype.Component;

@Component
public class SingleQueryResultFormatter extends StandardQueryResultFormatter {

    public SingleQueryResultFormatter() {
        super(IntentType.SINGLE_QUERY);
    }
}
