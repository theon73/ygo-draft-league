package com.ygoleague.image;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ThrottleTest {

    @Test
    void callersThatArriveTogetherAreSpacedOneIntervalApart() throws InterruptedException {
        AtomicLong now = new AtomicLong(1_000);
        List<Long> sleeps = new ArrayList<>();
        Throttle throttle = new Throttle(5, now::get, sleeps::add);   // one per 200 ms

        throttle.acquire();
        throttle.acquire();
        throttle.acquire();
        assertEquals(List.of(200_000_000L, 400_000_000L), sleeps);    // first goes straight away

        now.addAndGet(10_000_000_000L);                                // a long pause: no backlog
        throttle.acquire();
        assertEquals(2, sleeps.size());
    }
}
