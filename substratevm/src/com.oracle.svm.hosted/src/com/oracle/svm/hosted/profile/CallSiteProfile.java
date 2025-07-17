package com.oracle.svm.hosted.profile;

import org.graalvm.nativeimage.c.function.CodePointer;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

class CallSiteProfile {
    long totalCount;
    Map<Class<?>, Long> receiverCounts;
    String source;
    String targetMethod;
    CodePointer sourceCodePointer;

    protected List<Map.Entry<Class<?>, Long>> getTopReceiverClasses(int limit) {
        return receiverCounts.entrySet().stream()
            .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
            .limit(limit)
            .collect(Collectors.toList());
    }

    public CallSiteProfile(String source, String targetMethod, CodePointer sourceCodePointer) {
        this.totalCount = 0;
        this.receiverCounts = new HashMap<>();
        this.source = source;
        this.sourceCodePointer = sourceCodePointer;
        this.targetMethod = targetMethod;
    }
}