package com.company.paymentanalysis.agent;

import java.io.Serializable;

/** A typed view hint lets one Agent endpoint support distinct product panels. */
public record AgentViewModel(String type, Object payload) implements Serializable {
}
