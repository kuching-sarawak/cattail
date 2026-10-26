package net.kuching.cattail;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.kuching.cattail.helper.HelperTarget;
import org.junit.jupiter.api.Test;

class PlatformTest {

    @Test
    void helperSupportedFollowsHelperTarget() {
        assertEquals(HelperTarget.current().isPresent(), Platform.helperSupported());
    }
}
