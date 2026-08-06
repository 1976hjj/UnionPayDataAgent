package com.company.paymentanalysis.smartbi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import smartbi.net.sf.json.JSONObject;

class SmartBiDependencyTest {

    @Test
    void loadsEzmorphAndSerializesWithTheSmartBiJsonLibrary() throws Exception {
        assertThat(Class.forName("net.sf.ezmorph.MorpherRegistry")).isNotNull();

        JSONObject json = JSONObject.fromObject(Map.of("status", "ok"));

        assertThat(json.getString("status")).isEqualTo("ok");
    }
}
