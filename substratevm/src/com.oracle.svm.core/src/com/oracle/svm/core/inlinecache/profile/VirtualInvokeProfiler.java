package com.oracle.svm.core.inlinecache.profile;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class VirtualInvokeProfiler {
    static private final CallSiteProfile[] callSiteProfiles = new CallSiteProfile[100000];
    private static boolean profilingEnabled = false;
    private static boolean isInProfilerContext = false;

    public static void enableProfiling() {
        profilingEnabled = true;
    }

    static void profileVirtualInvoke(String source, Object receiver, int callSiteId) {
        if (!profilingEnabled || isInProfilerContext) {
            return;
        }

        isInProfilerContext = true;

        CallSiteProfile callSiteProfile = callSiteProfiles[callSiteId];

        if (callSiteProfile == null) {
            callSiteProfile = new CallSiteProfile(source);
            callSiteProfiles[callSiteId] = callSiteProfile;
        }

        Class<?> receiverClass = receiver.getClass();
        callSiteProfile.receiverCounts.put(receiverClass, callSiteProfile.receiverCounts.getOrDefault(receiverClass, 0L) + 1);
        callSiteProfile.totalCount++;

        isInProfilerContext = false;
    }

    public static void dumpProfileData() {
        isInProfilerContext = true;

        System.out.println("Dumping Virtual Invoke Profile Data:");
        System.out.println(
            Arrays.stream(callSiteProfiles)
                .filter(Objects::nonNull)
                .sorted((callSiteProfile1, callSiteProfile2) -> Long.compare(
                    callSiteProfile2.totalCount,
                    callSiteProfile1.totalCount
                ))
                .filter(callSiteProfile -> callSiteProfile.receiverCounts.size() >= 2 && callSiteProfile.totalCount >= 10000)
                .limit(100)
                .map(callSiteProfile -> String.format(
                    "Callsite %d: Total Count: %d, Source: %s\n%s",
                    Arrays.asList(callSiteProfiles).indexOf(callSiteProfile),
                    callSiteProfile.totalCount,
                    callSiteProfile.source,
                    callSiteProfile.receiverCounts.entrySet().stream()
                        .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                        .limit(25)
                        .map(entry -> String.format(
                            "Receiver Class: %s, Count: %d (%.2f%%)",
                            entry.getKey().getName(),
                            entry.getValue(),
                            (entry.getValue() * 100.0) / callSiteProfile.totalCount)
                        )
                        .collect(Collectors.joining("\n")
                    )
                ))
                .collect(Collectors.joining("\n\n"))
        );
    }

    private static class CallSiteProfile {
        long totalCount;
        Map<Class<?>, Long> receiverCounts;
        String source;

        public CallSiteProfile(String source) {
            this.totalCount = 0;
            this.receiverCounts = new HashMap<>();
            this.source = source;
        }
    }
}
