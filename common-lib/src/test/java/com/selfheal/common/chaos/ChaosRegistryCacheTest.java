package com.selfheal.common.chaos;

import com.selfheal.common.model.LinkFault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ChaosRegistryCacheTest {

    @Test
    void testCacheUpdateFromListJson() {
        ChaosRegistryCache cache = new ChaosRegistryCache();
        String json = """
            [
              {"caller":"compose-post","target":"text","delayMs":1500,"lossPct":10.0},
              {"caller":"compose-post","target":"post-storage","delayMs":0,"lossPct":50.0}
            ]
            """;

        cache.updateCache(json);

        LinkFault fault1 = cache.getLinkFault("compose-post", "text");
        assertNotNull(fault1);
        assertEquals(1500, fault1.getDelayMs());
        assertEquals(10.0, fault1.getLossPct());

        LinkFault fault2 = cache.getLinkFault("compose-post", "post-storage");
        assertNotNull(fault2);
        assertEquals(0, fault2.getDelayMs());
        assertEquals(50.0, fault2.getLossPct());

        assertNull(cache.getLinkFault("gateway", "compose-post"));
    }

    @Test
    void testCacheUpdateFromMapJson() {
        ChaosRegistryCache cache = new ChaosRegistryCache();
        String json = """
            {
              "compose-post->media": {"caller":"compose-post","target":"media","delayMs":800,"lossPct":0.0}
            }
            """;

        cache.updateCache(json);

        LinkFault fault = cache.getLinkFault("compose-post", "media");
        assertNotNull(fault);
        assertEquals(800, fault.getDelayMs());
    }
}
