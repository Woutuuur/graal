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
    static void profileVirtualInvoke(String source, String targetMethodName, Object receiver, int callSiteId) {
        if (!profilingEnabled || isInProfilerContext) {
            return;
        }

        isInProfilerContext = true;

        CallSiteProfile callSiteProfile = callSiteProfiles[callSiteId];

        if (callSiteProfile == null) {
            callSiteProfile = new CallSiteProfile(source, targetMethodName, KnownIntrinsics.readReturnAddress());
            callSiteProfiles[callSiteId] = callSiteProfile;
        }

        String receiverClassName = receiver.getClass().getName();
        callSiteProfile.receiverCounts.put(receiverClassName, callSiteProfile.receiverCounts.getOrDefault(receiverClassName, 0L) + 1);
        callSiteProfile.totalCount++;

        isInProfilerContext = false;
    }

    public static void dumpProfileData() {
        isInProfilerContext = true;

        List<CallSiteProfile> relevantProfiles = Arrays.stream(callSiteProfiles)
            .filter(Objects::nonNull)
            .filter(callSiteProfile -> callSiteProfile.receiverCounts.size() >= 2 && callSiteProfile.totalCount >= 10000)
            .sorted((callSiteProfile1, callSiteProfile2) -> Long.compare(
                    callSiteProfile2.totalCount,
                    callSiteProfile1.totalCount
            )).toList();

        System.out.println("Most relevant virtual invoke profile data:");
        System.out.println(
            relevantProfiles.stream()
            .limit(100)
            .map(callSiteProfile -> String.format(
                "Callsite %d: Target method: %s, Total Count: %d, Num unique callsites: %d, Approx. address: 0x%x, Source: %s\n%s",
                Arrays.asList(callSiteProfiles).indexOf(callSiteProfile),
                callSiteProfile.targetMethod,
                callSiteProfile.totalCount,
                callSiteProfile.receiverCounts.size(),
                callSiteProfile.sourceCodePointer.rawValue(),
                callSiteProfile.source,
                callSiteProfile.getTopReceiverClasses(25).stream()
                    .map(entry -> String.format(
                        "Receiver Class: %s, Count: %d (%.2f%%)",
                        entry.getKey(),
                        entry.getValue(),
                        (entry.getValue() * 100.0) / callSiteProfile.totalCount
                    ))
                    .collect(Collectors.joining("\n")
                )
            ))
            .collect(Collectors.joining("\n\n"))
        );

        System.out.println("Dumping virtual invoke profile data...");
        try (java.io.FileWriter fileWriter = new java.io.FileWriter("profiler-data.json")) {
//            fileWriter.write("[\n");
//            String jsonData = relevantProfiles.stream()
//                .map(callSiteProfile -> String.format(
//                    "  {\n    \"callsiteId\": %d,\n    \"targetMethod\": %s,\n    \"totalCount\": %d,\n    \"uniqueCallsites\": %d,\n    \"approxAddress\": \"0x%x\",\n    \"source\": \"%s\",\n    \"receiverCounts\": {\n%s\n    }\n  }",
//                    Arrays.asList(callSiteProfiles).indexOf(callSiteProfile),
//                    callSiteProfile.targetMethod,
//                    callSiteProfile.totalCount,
//                    callSiteProfile.receiverCounts.size(),
//                    callSiteProfile.sourceCodePointer.rawValue(),
//                    callSiteProfile.source,
//                    callSiteProfile.getTopReceiverClasses(999999).stream()
//                        .map(entry -> String.format("      \"%s\": %d", entry.getKey().getName(), entry.getValue()))
//                        .collect(Collectors.joining(",\n"))
//                ))
//                .collect(Collectors.joining(",\n"));
//            fileWriter.write(jsonData);
//            fileWriter.write("\n]\n");
            fileWriter.write(CallSiteProfile.toJSON(relevantProfiles));
        } catch (java.io.IOException e) {
            e.printStackTrace();
        }
    }
}
