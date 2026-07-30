package com.company.paymentanalysis.config;

import com.company.paymentanalysis.calculation.NullValuePolicy;
import java.math.RoundingMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "analysis")
public record AnalysisProperties(
        Calculation calculation,
        Ranking ranking,
        Trend trend,
        Comparison comparison) {

    public AnalysisProperties {
        calculation = calculation == null ? new Calculation(null, null) : calculation;
        ranking = ranking == null ? new Ranking(null, null, null, null) : ranking;
        trend = trend == null ? new Trend(null, null) : trend;
        comparison = comparison == null ? new Comparison(null, null) : comparison;
    }

    public record Calculation(Integer rateScale, RoundingMode roundingMode) {
        public Calculation {
            rateScale = rateScale == null ? 4 : rateScale;
            roundingMode = roundingMode == null ? RoundingMode.HALF_UP : roundingMode;
            if (rateScale < 0) {
                throw new IllegalArgumentException("analysis.calculation.rate-scale 不能小于 0");
            }
        }
    }

    public record Ranking(
            Integer defaultTopN,
            Integer maxTopN,
            RankingExecutionMode executionMode,
            NullValuePolicy nullValuePolicy) {
        public Ranking {
            defaultTopN = defaultTopN == null ? 10 : defaultTopN;
            maxTopN = maxTopN == null ? 100 : maxTopN;
            executionMode = executionMode == null
                    ? RankingExecutionMode.HYBRID
                    : executionMode;
            nullValuePolicy = nullValuePolicy == null
                    ? NullValuePolicy.EXCLUDE
                    : nullValuePolicy;
            if (defaultTopN <= 0 || maxTopN <= 0 || defaultTopN > maxTopN || maxTopN > 100) {
                throw new IllegalArgumentException("analysis.ranking 的 TopN 配置无效");
            }
        }
    }

    public record Trend(Boolean fillMissingPeriods, DuplicatePeriodPolicy duplicatePeriodPolicy) {
        public Trend {
            fillMissingPeriods = fillMissingPeriods == null ? false : fillMissingPeriods;
            duplicatePeriodPolicy = duplicatePeriodPolicy == null
                    ? DuplicatePeriodPolicy.ERROR
                    : duplicatePeriodPolicy;
        }
    }

    public record Comparison(Boolean preferGroupedQuery, Boolean fallbackToSeparateQuery) {
        public Comparison {
            preferGroupedQuery = preferGroupedQuery == null ? true : preferGroupedQuery;
            fallbackToSeparateQuery = fallbackToSeparateQuery == null
                    ? true
                    : fallbackToSeparateQuery;
        }
    }

    public enum RankingExecutionMode {
        JAVA,
        SMARTBI,
        HYBRID
    }

    public enum DuplicatePeriodPolicy {
        ERROR
    }
}
