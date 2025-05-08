package com.oracle.svm.core.inlinecache.profile;

import java.util.HashMap;
import java.util.Map;

public class VirtualInvokeProfiler {
    static private final CallSiteProfile[] callSiteProfiles = new CallSiteProfile[100000];
    private static volatile boolean profilingEnabled = false;
    private static volatile boolean isInProfilerContext = false;

    public static void enableProfiling() {
        profilingEnabled = true;
    }

    static void profileVirtualInvoke(Object receiver, int callSiteId) {
        if (!profilingEnabled || isInProfilerContext || receiver == null) {
            return;
        }

        isInProfilerContext = true;

        CallSiteProfile callSiteProfile = callSiteProfiles[callSiteId];

        if (callSiteProfile == null) {
            callSiteProfile = new CallSiteProfile();
            callSiteProfiles[callSiteId] = callSiteProfile;
        }

        Class<?> receiverClass = receiver.getClass();
        callSiteProfile.receiverCounts.put(receiverClass, callSiteProfile.receiverCounts.getOrDefault(receiverClass, 0L) + 1);
        callSiteProfile.totalCount++;

        if (callSiteProfile.totalCount % 100000000 == 0) {
            System.out.println("Profiling virtual invoke for call site ID: " + callSiteId + " with receiver " + receiver + " current: " + callSiteProfile.receiverCounts.get(receiverClass) + "/" +  callSiteProfile.totalCount);
        }

        isInProfilerContext = false;
    }

    private static class CallSiteProfile {
        long totalCount;
        Map<Class<?>, Long> receiverCounts;

        public CallSiteProfile() {
            this.totalCount = 0;
            this.receiverCounts = new HashMap<>();

        }
    }
}
