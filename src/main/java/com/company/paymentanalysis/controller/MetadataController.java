package com.company.paymentanalysis.controller;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/metadata")
public class MetadataController {

    @GetMapping
    public MetadataResponse metadata() {
        return new MetadataResponse(
                "mock-v1",
                List.of(
                        new Metric("transactionAmount", "交易金额", "元", "currency"),
                        new Metric("transactionCount", "交易笔数", "笔", "integer"),
                        new Metric("successRate", "支付成功率", "%", "percent")),
                List.of(
                        new Dimension("tradeYear", "年"),
                        new Dimension("tradeMonth", "月"),
                        new Dimension("tradeDate", "日"),
                        new Dimension("channel", "受理渠道"),
                        new Dimension("region", "地区"),
                        new Dimension("merchantType", "商户类型"),
                        new Dimension("paymentMethod", "支付方式")));
    }

    public record MetadataResponse(
            String version,
            List<Metric> metrics,
            List<Dimension> dimensions) {
    }

    public record Metric(String id, String name, String unit, String format) {
    }

    public record Dimension(String id, String name) {
    }
}
