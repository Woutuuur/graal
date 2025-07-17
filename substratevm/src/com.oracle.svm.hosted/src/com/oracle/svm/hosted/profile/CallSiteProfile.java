package com.oracle.svm.hosted.profile;

import org.graalvm.nativeimage.c.function.CodePointer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class CallSiteProfile {
    long totalCount;
    Map<String, Long> receiverCounts;
    String source;
    String targetMethod;
    CodePointer sourceCodePointer;

    protected List<Map.Entry<String, Long>> getTopReceiverClasses(int limit) {
        return receiverCounts.entrySet().stream()
            .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
            .limit(limit)
            .collect(Collectors.toList());
    }

    public static List<CallSiteProfile> loadFromJSON(String json) {
        List<CallSiteProfile> profiles = new ArrayList<>();
        Pattern pattern = Pattern.compile(
            "\\{\\s*\"targetMethod\":\\s*\"([^\"]+)\",\\s*" +
            "\"totalCount\":\\s*(\\d+),\\s*" +
            "\"uniqueCallsites\":\\s*\\d+,\\s*" +
            "\"approxAddress\":\\s*\"(0x[0-9a-fA-F]+)\",\\s*" +
            "\"source\":\\s*\"([^\"]+)\",\\s*" +
            "\"receiverCounts\":\\s*\\{([^}]*)}\\s*}");
        Matcher matcher = pattern.matcher(json);
        while (matcher.find()) {
            String targetMethod = matcher.group(1);
            long totalCount = Long.parseLong(matcher.group(2));
            String source = matcher.group(4);

            Map<String, Long> receiverCounts = new HashMap<>();
            String receiverCountsStr = matcher.group(5);
            Pattern receiverPattern = Pattern.compile("\"([^\"]+)\":\\s*(\\d+)");
            Matcher receiverMatcher = receiverPattern.matcher(receiverCountsStr);
            while (receiverMatcher.find()) {
                String receiverClassName = receiverMatcher.group(1);
                long count = Long.parseLong(receiverMatcher.group(2));
                receiverCounts.put(receiverClassName, count);
            }

            CallSiteProfile profile = new CallSiteProfile(totalCount, receiverCounts, source, targetMethod);
            profiles.add(profile);
        }

        return profiles;
    }

    public static String toJSON(List<CallSiteProfile> profiles) {
        return "[\n" +
            profiles.stream()
            .map(callSiteProfile -> String.format(
                "  {\n    \"targetMethod\": \"%s\",\n    \"totalCount\": %d,\n    \"uniqueCallsites\": %d,\n    \"approxAddress\": \"0x%x\",\n    \"source\": \"%s\",\n    \"receiverCounts\": {\n%s\n    }\n  }",
                callSiteProfile.targetMethod,
                callSiteProfile.totalCount,
                callSiteProfile.receiverCounts.size(),
                callSiteProfile.sourceCodePointer.rawValue(),
                callSiteProfile.source,
                callSiteProfile.getTopReceiverClasses(999999).stream()
                    .map(entry -> String.format("      \"%s\": %d", entry.getKey(), entry.getValue()))
                    .collect(Collectors.joining(",\n"))
            )).collect(Collectors.joining(",\n")) +
            "\n]\n";
    }

    public CallSiteProfile(long totalCount, Map<String, Long> receiverCounts, String source, String targetMethod) {
        this.totalCount = totalCount;
        this.receiverCounts = receiverCounts;
        this.source = source;
        this.targetMethod = targetMethod;
    }

    public CallSiteProfile(String source, String targetMethod, CodePointer sourceCodePointer) {
        this.totalCount = 0;
        this.receiverCounts = new HashMap<>();
        this.source = source;
        this.sourceCodePointer = sourceCodePointer;
        this.targetMethod = targetMethod;
    }
}