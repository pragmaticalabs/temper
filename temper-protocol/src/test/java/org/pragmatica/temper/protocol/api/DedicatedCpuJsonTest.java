package org.pragmatica.temper.protocol.api;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.protocol.json.TemperJson;

import static org.assertj.core.api.Assertions.assertThat;


/// m4-design §9.2: `dedicatedCpu` on the submit and rerun bodies. Absent reads as false, so a body without it (an older
/// CLI) submits exactly what it did before M4; true reaches the job request and its options (the dedup key, §9.3).
class DedicatedCpuJsonTest {
    private static final String SUBMIT = "{\"submittedBy\":\"v2174\",\"source\":{\"pushed\":{\"ref\":\"main\"}},"
                                        + "\"recipe\":{\"gate\":{}}";

    @Test
    void submitRequest_dedicatedCpuTrue_reachesTheJobRequestAndItsOptions() {
        var request = TemperJson.mapper().readString(SUBMIT + ",\"dedicatedCpu\":true}", SubmitRequest.class).unwrap();
        var job = request.toDomain().unwrap();

        assertThat(job.dedicatedCpu()).isTrue();
        assertThat(job.options().dedicatedCpu()).isTrue();
    }

    @Test
    void submitRequest_dedicatedCpuAbsentOrFalse_isFalse() {
        var absent = TemperJson.mapper().readString(SUBMIT + "}", SubmitRequest.class).unwrap();
        var off = TemperJson.mapper().readString(SUBMIT + ",\"dedicatedCpu\":false}", SubmitRequest.class).unwrap();

        assertThat(absent.toDomain().unwrap().dedicatedCpu()).isFalse();
        assertThat(off.toDomain().unwrap().dedicatedCpu()).isFalse();
    }

    @Test
    void submitRequest_forTask_keepsDedicatedCpu() {
        var request = TemperJson.mapper().readString(SUBMIT + ",\"dedicatedCpu\":true}", SubmitRequest.class).unwrap();

        assertThat(request.forTask(3, 1).toDomain().unwrap().dedicatedCpu()).isTrue();
    }

    @Test
    void rerunRequest_readsDedicatedCpu_absentAsNone() {
        var on = TemperJson.mapper().readString("{\"submittedBy\":\"v2174\",\"dedicatedCpu\":true}", RerunRequest.class).unwrap();
        var absent = TemperJson.mapper().readString("{\"submittedBy\":\"v2174\"}", RerunRequest.class).unwrap();

        assertThat(on.dedicatedCpu().or(false)).isTrue();
        assertThat(absent.dedicatedCpu().isPresent()).isFalse();
    }
}
