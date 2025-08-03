package com.oracle.svm.hosted.profile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class CallSiteProfile implements Comparable<CallSiteProfile> {
    long totalCount;
    HashMap<String, Long> receiverCounts;
    String source;
    String targetMethod;
    boolean isDirectCall;

    public String getSource() {
        return source;
    }

    public String getTargetMethod() {
        return targetMethod;
    }

    public boolean isDirectCall() {
        return isDirectCall;
    }

    public long getTotalCount() {
        return totalCount;
    }

    protected List<Map.Entry<String, Long>> getTopReceiverClasses(Integer limit) {
        return new ArrayList<>(receiverCounts.entrySet());
    }

    public static List<CallSiteProfile> loadFromJSON(String json) {
        List<CallSiteProfile> profiles = new ArrayList<>();
        Pattern pattern = Pattern.compile(
            "\\{\\s*\"targetMethod\": \"([^\"]+)\",\\s*" +
            "\"totalCount\": (\\d+),\\s*" +
            "\"uniqueCallsites\": \\d+,\\s*" +
            "\"source\": \"([^\"]+)\",\\s*" +
            "\"isDirectCall\": (true|false),\\s*" +
            "\"receiverCounts\": \\{([^}]*)}\\s*}");
        Matcher matcher = pattern.matcher(json);
        while (matcher.find()) {
            String targetMethod = matcher.group(1);
            long totalCount = Long.parseLong(matcher.group(2));
            String source = matcher.group(3);
            boolean isDirectCall = Boolean.parseBoolean(matcher.group(4));

            Map<String, Long> receiverCounts = new HashMap<>();
            String receiverCountsStr = matcher.group(5);
            Pattern receiverPattern = Pattern.compile("\"([^\"]+)\":\\s*(\\d+)");
            Matcher receiverMatcher = receiverPattern.matcher(receiverCountsStr);
            while (receiverMatcher.find()) {
                String receiverClassName = receiverMatcher.group(1);
                long count = Long.parseLong(receiverMatcher.group(2));
                receiverCounts.put(receiverClassName, count);
            }

            CallSiteProfile profile = new CallSiteProfile(totalCount, receiverCounts, source, targetMethod, isDirectCall);
            profiles.add(profile);
        }

        return profiles;
    }

    public static String toJSON(List<CallSiteProfile> profiles) {
        return "[\n" + profiles.stream()
            .map(callSiteProfile -> String.format(
                "  {\n    \"targetMethod\": \"%s\",\n    \"totalCount\": %d,\n    \"uniqueCallsites\": %d,\n    \"source\": \"%s\",\n    \"isDirectCall\": %s,\n    \"receiverCounts\": {\n%s\n    }\n  }",
                callSiteProfile.targetMethod,
                callSiteProfile.totalCount,
                callSiteProfile.receiverCounts.size(),
                callSiteProfile.source,
                callSiteProfile.isDirectCall ? "true" : "false",
                callSiteProfile.receiverCounts.entrySet().stream()
                    .map(entry -> String.format("      \"%s\": %d", entry.getKey(), entry.getValue()))
                    .collect(Collectors.joining(",\n"))
            ))
            .collect(Collectors.joining(",\n")) +
        "\n]\n";
    }

    public CallSiteProfile(long totalCount, Map<String, Long> receiverCounts, String source, String targetMethod, boolean isDirectCall) {
        this.totalCount = totalCount;
        this.receiverCounts = new HashMap<>(receiverCounts);
        this.source = source;
        this.targetMethod = targetMethod;
        this.isDirectCall = isDirectCall;
    }

    public CallSiteProfile(String source, String targetMethod, boolean isDirectCall) {
        this.receiverCounts = new HashMap<>(100);
        this.source = source;
        this.targetMethod = targetMethod;
        this.isDirectCall = isDirectCall;
        this.totalCount = 0;
    }

    public String toString() {
        return String.format("CallSiteProfile{source='%s', targetMethod='%s', totalCount=%d, receiverCounts=%s}",
                source, targetMethod, totalCount, receiverCounts);
    }

    @Override
    public int compareTo(CallSiteProfile o) {
        return Long.compare(o.totalCount, this.totalCount);
    }

}