package com.company.paymentanalysis.formatter;

import com.company.paymentanalysis.analysis.IntentType;
import org.springframework.stereotype.Component;

@Component
public class GroupQueryResultFormatter extends StandardQueryResultFormatter {

    public GroupQueryResultFormatter() {
        super(IntentType.GROUP_QUERY);
    }
}
