package com.company.paymentanalysis.normalize;

import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import java.util.List;

public interface SmartBiResultNormalizer {

    List<NormalizedDataRow> normalize(QueryResponse response, QueryPlan queryPlan);
}
