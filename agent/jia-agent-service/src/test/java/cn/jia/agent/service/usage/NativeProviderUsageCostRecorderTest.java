package cn.jia.agent.service.usage;
import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.entity.PersonalWorkspaceExecutionEntity;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.economy.pricing.ModelPriceCatalog;
import cn.jia.economy.pricing.ModelTokenPrice;
import cn.jia.economy.usage.UsageCostService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class NativeProviderUsageCostRecorderTest {
    final PersonalWorkspaceExecutionDao executions=mock(PersonalWorkspaceExecutionDao.class);
    final UsageCostService costs=mock(UsageCostService.class);
    final ModelPriceCatalog prices=mock(ModelPriceCatalog.class);
    final NativeProviderUsageCostRecorder recorder=new NativeProviderUsageCostRecorder(executions,costs,prices);
    final PersonalWorkspaceExecutionService.RuntimeScope scope=new PersonalWorkspaceExecutionService.RuntimeScope("0","client","executor","agent","instance");
    final ModelTokenPrice price=new ModelTokenPrice("gpt","image","standard","v1","fixture",Map.of(ModelTokenPrice.Category.IMAGE_OUTPUT,new BigDecimal("30")),null,Map.of());
    NativeProviderUsageCostRecorder.Evidence evidence(String call,NativeExecutionUsageCostRecorder.Phase phase) {
        return new NativeProviderUsageCostRecorder.Evidence(1,call,"openai-images-v1","gpt","standard","v1","image","generate",phase,1234,
            phase==NativeExecutionUsageCostRecorder.Phase.STARTED?null:new ObjectMapper().readTree("{\"usage\":{\"input_tokens\":0,\"output_tokens\":1,\"input_tokens_details\":{\"text_tokens\":0,\"image_tokens\":0}}}"));
    }
    void authorize() { when(executions.findForNativeUsageCost("0","client","executor","agent","task","run","exec"))
            .thenReturn(new PersonalWorkspaceExecutionEntity().setOwnerJiacn("owner").setTaskId("task").setExecutionState("CANCELLED")); }
    @Test void eachProviderCallHasIndependentAttemptAndHistoricalTaskOwner() {
        authorize();when(prices.find("gpt","image","standard")).thenReturn(Optional.of(price));
        recorder.record(scope,"task","run","exec",evidence("call-a",NativeExecutionUsageCostRecorder.Phase.STARTED));
        recorder.record(scope,"task","run","exec",evidence("call-b",NativeExecutionUsageCostRecorder.Phase.STARTED));
        var captor=ArgumentCaptor.forClass(UsageCostService.Observation.class);verify(costs,times(2)).record(captor.capture());
        var a=captor.getAllValues().get(0);var b=captor.getAllValues().get(1);
        assertEquals("owner",a.binding().ownerJiacn());assertEquals("task",a.binding().taskId());assertEquals("NATIVE_PROVIDER_CALL",a.binding().source());
        assertNotEquals(a.binding().sourceAttemptId(),b.binding().sourceAttemptId());assertEquals(price,a.price());assertNull(a.normalizedUsage());
    }
    @Test void terminalUsesRetainedStartPriceAndRetryUsesOriginalSnapshotWithoutCatalogLookup() {
        authorize();var binding=new UsageCostService.Binding(new cn.jia.economy.service.EconomyScope("0","client"),"owner","task","NATIVE_PROVIDER_CALL","exec:call");
        when(costs.observation(any(),eq("started"))).thenReturn(Optional.of(new UsageCostService.Observation(binding,"started",0,"{}",null,price,1)));
        recorder.record(scope,"task","run","exec",evidence("call",NativeExecutionUsageCostRecorder.Phase.TERMINAL));
        var captor=ArgumentCaptor.forClass(UsageCostService.Observation.class);verify(costs).record(captor.capture());
        var saved=captor.getValue();assertEquals(price,saved.price());assertNotNull(saved.normalizedUsage());assertEquals(1,saved.expectedRevision());
        when(costs.observation(any(),eq("terminal"))).thenReturn(Optional.of(saved));
        recorder.record(scope,"task","run","exec",evidence("call",NativeExecutionUsageCostRecorder.Phase.TERMINAL));
        verify(costs,times(2)).record(saved);verifyNoInteractions(prices);
    }
    @Test void unauthorisedExecutionNeverReadsPricesOrWritesMoney() {
        assertThrows(PersonalWorkspaceExecutionService.Failure.class,()->recorder.record(scope,"task","run","exec",evidence("call",NativeExecutionUsageCostRecorder.Phase.STARTED)));
        verifyNoInteractions(costs,prices);
    }
}
