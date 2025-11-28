package com.oracle.svm.hosted.profile;

import com.oracle.svm.core.heap.NoAllocationVerifier;
import jdk.graal.compiler.java.CallSiteProfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class InvokeProfiler {
    static private final CallSiteProfile[] callSiteProfiles = new CallSiteProfile[10000000];
    private static boolean profilingEnabled = false;
    private static volatile boolean profilingInProgress = false;

    public static void enableProfiling() {
        profilingEnabled = true;
    }

    static void profileInvoke(boolean isDirect, String source, String targetMethodName, Object receiver, int callSiteId) {
        if (!profilingEnabled || NoAllocationVerifier.isActive() || profilingInProgress) return;

        CallSiteProfile callSiteProfile = callSiteProfiles[callSiteId];

        if (callSiteProfile == null) {
            callSiteProfile = new CallSiteProfile(source, targetMethodName, isDirect);
            callSiteProfiles[callSiteId] = callSiteProfile;
        }

        String receiverClassName = isDirect ? (String) receiver : receiver.getClass().getName();
        Long currentCount = callSiteProfile.receiverCounts.putIfAbsent(receiverClassName, 1L);
        if (currentCount != null) {
            callSiteProfile.receiverCounts.put(receiverClassName, currentCount + 1);
        }
        callSiteProfile.totalCount++;
    }

    public static void dumpProfileData() {
        profilingInProgress = true;

        long minUniqueReceiverCount = 1;
        long minTotalCount = 0;

        try {
            List<CallSiteProfile> relevantProfiles = Arrays.stream(callSiteProfiles)
                .filter(Objects::nonNull)
                .filter(callSiteProfile -> callSiteProfile.receiverCounts.size() >= minUniqueReceiverCount && callSiteProfile.totalCount >= minTotalCount)
                .sorted((callSiteProfile1, callSiteProfile2) -> Long.compare(
                    callSiteProfile2.totalCount,
                    callSiteProfile1.totalCount
                ))
                .toList();

            List<CallSiteProfile> uniqueRelevantProfiles = new ArrayList<>();

            for (CallSiteProfile profile : relevantProfiles) {
                if (profile.source == null || profile.targetMethod == null) {
                    System.out.println("Skipping profile with null source or target method: " + profile);
                    continue;
                }
                if (uniqueRelevantProfiles.stream().noneMatch(p -> p.source.equals(profile.source) && p.targetMethod.equals(profile.targetMethod))) {
                    uniqueRelevantProfiles.add(profile);
                }
            }

            try (java.io.FileWriter fileWriter = new java.io.FileWriter("profiler-data.json")) {
                System.out.println("Dumping virtual invoke profile data...");
                fileWriter.write(CallSiteProfile.toJSON(uniqueRelevantProfiles));
            } catch (java.io.IOException e) {
                e.printStackTrace();
            }
        } catch (Exception e) {
            System.out.println("Error while dumping profile data: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
