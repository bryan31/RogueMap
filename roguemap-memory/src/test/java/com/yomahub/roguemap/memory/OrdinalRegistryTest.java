package com.yomahub.roguemap.memory;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class OrdinalRegistryTest {

    @Test
    void registerAndLookup() {
        OrdinalRegistry reg = new OrdinalRegistry();
        String uuid = UUID.randomUUID().toString();
        int ordinal = reg.register(uuid);
        assertEquals(0, ordinal);
        assertEquals(uuid, reg.getId(ordinal));
        assertEquals(ordinal, reg.getOrdinal(uuid));
    }

    @Test
    void multipleRegistrations() {
        OrdinalRegistry reg = new OrdinalRegistry();
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        int oa = reg.register(a);
        int ob = reg.register(b);
        assertEquals(0, oa);
        assertEquals(1, ob);
        assertEquals(a, reg.getId(0));
        assertEquals(b, reg.getId(1));
    }

    @Test
    void releaseAndReuse() {
        OrdinalRegistry reg = new OrdinalRegistry();
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        String c = UUID.randomUUID().toString();
        int oa = reg.register(a);
        reg.register(b);
        reg.release(a);
        assertEquals(-1, reg.getOrdinal(a));
        assertNull(reg.getId(oa));
        int oc = reg.register(c);
        assertEquals(oa, oc); // ordinal 0 reused
        assertEquals(c, reg.getId(oc));
    }

    @Test
    void unknownIdReturnsMinusOne() {
        OrdinalRegistry reg = new OrdinalRegistry();
        assertEquals(-1, reg.getOrdinal("not-registered"));
    }

    @Test
    void serializeDeserializeRoundTrip() throws Exception {
        OrdinalRegistry reg = new OrdinalRegistry();
        String u1 = UUID.randomUUID().toString();
        String u2 = UUID.randomUUID().toString();
        int o1 = reg.register(u1);
        int o2 = reg.register(u2);

        byte[] data = reg.serialize();
        OrdinalRegistry restored = OrdinalRegistry.deserialize(data);

        assertEquals(u1, restored.getId(o1));
        assertEquals(u2, restored.getId(o2));
        assertEquals(o1, restored.getOrdinal(u1));
        assertEquals(o2, restored.getOrdinal(u2));
    }

    @Test
    void serializeSkipsReleasedEntries() throws Exception {
        OrdinalRegistry reg = new OrdinalRegistry();
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        reg.register(a);
        reg.register(b);
        reg.release(a);

        byte[] data = reg.serialize();
        OrdinalRegistry restored = OrdinalRegistry.deserialize(data);

        assertEquals(-1, restored.getOrdinal(a));
        assertEquals(1, restored.getOrdinal(b));
    }
}
