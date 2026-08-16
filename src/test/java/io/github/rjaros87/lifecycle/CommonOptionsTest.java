package io.github.rjaros87.lifecycle;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommonOptionsTest {

    @Test
    void timeoutConvertsSecondsToDuration() {
        CommonOptions options = new CommonOptions();
        options.timeoutSeconds = 7;

        assertEquals(Duration.ofSeconds(7), options.timeout());
    }
}
