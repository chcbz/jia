package cn.jia.economy.pricing;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.jia.economy.pricing.ModelTokenPrice.Category.*;
class TaskWorkloadTest {
 @Test void passesModelTokensWithoutCoefficientsOrCacheDiscount() {
  var work=new TaskWorkload("DOCUMENT",20000,5000,1,Map.of());
  assertEquals(Map.of(INPUT,20000L,OUTPUT,5000L),work.estimate().tokens());
  assertEquals("MODEL_TOKEN_ESTIMATE",work.estimate().ruleId());
 }
 @Test void multipliesInputAndOutputSeparatelyAndRoundsOnlyTheFinalTotal() {
  var work=new TaskWorkload("DOCUMENT",20000,5000,1,Map.of());
  var price=new ModelTokenPrice("test-channel","test-model","standard","v1","test-fixture",
    Map.of(INPUT,new BigDecimal("0.668790"),OUTPUT,new BigDecimal("3.343950")),null,Map.of());
  var quote=TaskQuoteCalculator.calculate(work.estimate(),price,Map.of());
  assertEquals(0,new BigDecimal("0.03009555").compareTo(quote.estimatedYuan()));
  assertEquals(4L,quote.amountWen());
 }
 @Test void rejectsNegativeOrEntirelyMissingBudgetButAllowsSingleZeroCategory() {
  assertThrows(IllegalArgumentException.class,()->new TaskWorkload("CODE",-1,1,1,Map.of()));
  assertThrows(IllegalArgumentException.class,()->new TaskWorkload("CODE",1,-1,1,Map.of()));
  assertThrows(IllegalArgumentException.class,()->new TaskWorkload("CODE",0,0,1,Map.of()));
  assertThrows(IllegalArgumentException.class,()->new TaskWorkload("CODE",1,1,0,Map.of()));
  assertEquals(0L,new TaskWorkload("CODE",1,0,1,Map.of()).estimate().tokens().get(OUTPUT));
 }
 @Test void preservesSeparateToolUnitsAndFreezesCallerMap() {
  var key=new TaskQuoteCalculator.ToolKey("channel","image","image");
  var units=new HashMap<TaskQuoteCalculator.ToolKey,BigDecimal>();units.put(key,new BigDecimal("2"));
  var work=new TaskWorkload("MEDIA",1,1,2,units);units.clear();
  assertEquals(new BigDecimal("2"),work.estimate().toolUnits().get(key));
 }
 @Test void doesNotSumTokenLongsOrMultiplyByUnrelatedCounts() {
  assertEquals(Long.MAX_VALUE,new TaskWorkload("CODE",Long.MAX_VALUE,Long.MAX_VALUE,9,Map.of()).estimate().tokens().get(INPUT));
 }
}
