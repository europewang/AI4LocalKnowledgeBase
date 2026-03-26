package com.ai4kb.backend.engine.service;

import com.ai4kb.backend.engine.entity.RouteSample;
import com.ai4kb.backend.engine.mapper.RouteSampleMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.List;

class RouteSampleServiceTest {

    @Test
    void listSources_shouldNormalizeAndDistinct() {
        RouteSampleMapper mapper = Mockito.mock(RouteSampleMapper.class);
        RouteSampleService service = new RouteSampleService(mapper);
        Mockito.when(mapper.selectObjs(Mockito.any())).thenReturn(Arrays.asList("planner_only", "LOCAL_DIRECT", "", null, "PLANNER_ONLY"));

        List<String> result = service.listSources();

        Assertions.assertEquals(List.of("PLANNER_ONLY", "LOCAL_DIRECT"), result);
        Mockito.verify(mapper, Mockito.times(1)).selectObjs(Mockito.any());
    }

    @Test
    void listSamples_shouldClampLimitAndTrimSource() {
        RouteSampleMapper mapper = Mockito.mock(RouteSampleMapper.class);
        RouteSampleService service = new RouteSampleService(mapper);
        Mockito.when(mapper.selectList(Mockito.any())).thenReturn(List.of(new RouteSample()));

        List<RouteSample> result = service.listSamples(9999, 1L, "  planner_only ");

        Assertions.assertEquals(1, result.size());
        Mockito.verify(mapper, Mockito.times(1)).selectList(Mockito.any());
    }
}
