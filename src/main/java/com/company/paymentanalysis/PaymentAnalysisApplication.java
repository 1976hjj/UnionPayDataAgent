package com.company.paymentanalysis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PaymentAnalysisApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentAnalysisApplication.class, args);
    }
}
