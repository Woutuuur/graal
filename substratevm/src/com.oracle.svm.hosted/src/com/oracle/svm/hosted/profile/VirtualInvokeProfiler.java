package com.oracle.svm.hosted.profile;

import com.oracle.svm.core.NeverInline;
import com.oracle.svm.core.snippets.KnownIntrinsics;
import org.graalvm.nativeimage.c.function.CodePointer;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
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

    static void foo() {
        System.out.println("foo");
    }

    static void bar() {
        System.out.println("bar");
    }

    @NeverInline("Safe return address retrieval")
    static void profileVirtualInvoke(boolean isDirect, String source, String targetMethodName, Object receiver, int callSiteId) {
        if (!profilingEnabled || isInProfilerContext) {
            return;
        }

        isInProfilerContext = true;

        CallSiteProfile callSiteProfile = callSiteProfiles[callSiteId];

        if (callSiteProfile == null) {
            callSiteProfile = new CallSiteProfile(source, targetMethodName, KnownIntrinsics.readReturnAddress(), isDirect);
            callSiteProfiles[callSiteId] = callSiteProfile;
        }

        String receiverClassName = receiver instanceof String ? (String) receiver : receiver.getClass().getName();
        callSiteProfile.receiverCounts.put(receiverClassName, callSiteProfile.receiverCounts.getOrDefault(receiverClassName, 0L) + 1);
        callSiteProfile.totalCount++;

        isInProfilerContext = false;
    }

    public static void dumpProfileData() {
        isInProfilerContext = true;

        long minUniqueReceiverCount = 1;

        List<CallSiteProfile> relevantProfiles = Arrays.stream(callSiteProfiles)
            .filter(Objects::nonNull)
            .filter(callSiteProfile -> callSiteProfile.receiverCounts.size() >= minUniqueReceiverCount && callSiteProfile.totalCount >= 10000)
            .sorted((callSiteProfile1, callSiteProfile2) -> Long.compare(
                callSiteProfile2.totalCount,
                callSiteProfile1.totalCount
            )).toList();

        try (java.io.FileWriter fileWriter = new java.io.FileWriter("profiler-data.json")) {
            System.out.println("Dumping virtual invoke profile data...");
            fileWriter.write(CallSiteProfile.toJSON(relevantProfiles));
        } catch (java.io.IOException e) {
            e.printStackTrace();
        }
    }
}
