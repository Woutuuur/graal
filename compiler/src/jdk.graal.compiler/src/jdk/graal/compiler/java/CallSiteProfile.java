package jdk.graal.compiler.java;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class CallSiteProfile implements Comparable<CallSiteProfile> {
    public long totalCount;
    public HashMap<String, Long> receiverCounts;
    public String source;
    public String targetMethod;
    boolean isDirectCall;
    public final Map<String, Method> receiverNameConcreteMethods = new HashMap<>();

    public boolean isInlineCachedIndirectCall = false;

    boolean isMatched = false;

    public String getSource() {
        return source;
    }

    public String getTargetMethod() {
        return targetMethod;
    }

    public boolean isDirectCall() {
        return isDirectCall;
    }

    public boolean isIndirectCall() {
        return !isDirectCall;
    }

    public long getTotalCount() {
        return totalCount;
    }

    public Map<String, Long> getReceiverCounts() {
        return receiverCounts;
    }

    public void setIsMatched(boolean isUsedForInlining) {
        this.isMatched = isUsedForInlining;
    }

    public boolean isMatched() {
        return isMatched;
    }

    public int numPolymorphismCasesHeuristic() {
        // WIP Heuristic:
        // - If the top receiver count is more than 80% of total, consider it monomorphic
        // - If the top 2 receiver counts together are more than 80% of total, consider it bimorphic
        // - If the top 3 receiver counts together are more than 80% of total, consider it trimorphic
        // - Otherwise, consider it megamorphic (4+), which we don't include for now, so return 0 and just fallback to the non IC fallback
        // - Each case must make up at least 20% of the total to be considered, so once we find a case that doesn't meet that, we stop counting further cases

        List<String> candidates = this.getTopReceiverClassNames(3);
        long cumulativeCount = 0;

        for (int i = 0; i < candidates.size(); i++) {
            String className = candidates.get(i);
            long count = this.receiverCounts.get(className);
            cumulativeCount += count;
            double percentage = (double) cumulativeCount / (double) this.totalCount;

            if (percentage >= 0.8) {
                return i + 1;
            }

            if ((double) count / (double) this.totalCount < 0.2) {
                return 0;
            }
        }

        return 0;
    }

    public String getTargetClassName() {
        int lastDotIndex = this.getTargetMethod().lastIndexOf('.');
        return this.getTargetMethod().substring(0, lastDotIndex);
    }

    public Method getTopReceiverConcreteMethod() {
        List<Method> topMethods = this.getTopReceiverConcreteMethods(1);
        return topMethods.isEmpty() ? null : topMethods.getFirst();
    }

    public List<String> getTopReceiverClassNames(int n) {
        return this.getReceiverCounts().entrySet().stream()
            .sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue()))
            .limit(n)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    public String getTopReceiverConcreteClassName() {
        List<String> topTargets = this.getTopReceiverClassNames(1);
        return topTargets.isEmpty() ? null : topTargets.getFirst();
    }

    public Long getTopReceiverCount() {
        String topClassName = this.getTopReceiverConcreteClassName();
        return topClassName == null ? 0L : this.receiverCounts.get(topClassName);
    }

    public List<Method> getTopReceiverConcreteMethods(int n) {
        return this.getTopReceiverClassNames(n).stream()
            .map(this.receiverNameConcreteMethods::get)
            .collect(Collectors.toList());
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

    @Override
    public int hashCode() {
        return (source + "->" + targetMethod).hashCode();
    }
}
