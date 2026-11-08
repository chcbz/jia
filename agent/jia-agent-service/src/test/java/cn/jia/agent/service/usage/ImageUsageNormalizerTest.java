package cn.jia.agent.service.usage;
import cn.jia.economy.pricing.ModelTokenPrice;
import cn.jia.economy.usage.TokenUsageCost;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Map;
import static cn.jia.economy.pricing.ModelTokenPrice.Category.*;
import static org.junit.jupiter.api.Assertions.*;
class ImageUsageNormalizerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private TokenUsageCost.Usage normalize(String usage) {
        return ImageUsageNormalizer.normalize("gpt", "gpt-image-2.5-flare", "standard", JSON.readTree("{\"usage\":"+usage+"}"));
    }
    @Test void actualModalitiesAndCacheAreDisjointAndPricedInCny() {
        var usage = normalize("""
          {"input_tokens":1000,"output_tokens":200,"total_tokens":1200,
           "input_tokens_details":{"text_tokens":400,"image_tokens":600,"cached_tokens":150,
             "cached_tokens_details":{"text_tokens":50,"image_tokens":100}}}
          """);
        assertEquals(Map.of(INPUT,350L,CACHED_INPUT,50L,IMAGE_INPUT,500L,CACHED_IMAGE_INPUT,100L,IMAGE_OUTPUT,200L),usage.tokens());
        var price = new ModelTokenPrice("gpt","gpt-image-2.5-flare","standard","fixture","synthetic usage; registered rates",Map.of(
                INPUT,new BigDecimal("33.439500"), CACHED_INPUT,new BigDecimal("8.359875"),
                IMAGE_INPUT,new BigDecimal("53.503200"),CACHED_IMAGE_INPUT,new BigDecimal("13.375800"),IMAGE_OUTPUT,new BigDecimal("200.637000")),null,Map.of());
        assertEquals(new BigDecimal("0.080338"), TokenUsageCost.assess(usage,price).costYuan());
    }
    @Test void noUsageAmbiguousCacheAndInconsistentCountersStayPending() {
        for (String usage : new String[]{"null", "{}",
                "{\"input_tokens\":2,\"output_tokens\":1,\"input_tokens_details\":{\"text_tokens\":1,\"image_tokens\":1,\"cached_tokens\":1}}",
                "{\"input_tokens\":3,\"output_tokens\":1,\"input_tokens_details\":{\"text_tokens\":1,\"image_tokens\":1}}",
                "{\"input_tokens\":2,\"output_tokens\":-1,\"input_tokens_details\":{\"text_tokens\":1,\"image_tokens\":1}}"}) assertNull(normalize(usage));
    }
    @Test void explicitZeroIsPricedButAbsentUsageIsNotZero() {
        var usage = normalize("{\"input_tokens\":0,\"output_tokens\":0,\"input_tokens_details\":{\"text_tokens\":0,\"image_tokens\":0}}");
        assertNotNull(usage);
        assertEquals(TokenUsageCost.Status.PENDING_PRICE,TokenUsageCost.assess(usage,null).status());
        assertEquals(TokenUsageCost.Status.PENDING_USAGE,TokenUsageCost.assess(null,null).status());
    }
}
