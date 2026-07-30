package com.company.paymentanalysis.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {

    @GetMapping({"/query", "/attribution"})
    public String forwardToIndex() {
        return "forward:/index.html";
    }
}

