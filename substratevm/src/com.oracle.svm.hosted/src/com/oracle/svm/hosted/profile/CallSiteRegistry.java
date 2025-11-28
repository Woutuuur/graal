package com.oracle.svm.hosted.profile;

import java.util.concurrent.atomic.AtomicInteger;

public class CallSiteRegistry {
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    public static int allocateId() {
        return NEXT_ID.getAndIncrement();
    }
}
