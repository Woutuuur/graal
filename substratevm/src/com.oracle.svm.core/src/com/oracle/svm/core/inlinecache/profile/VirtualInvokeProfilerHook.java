package com.oracle.svm.core.inlinecache.profile;

import com.oracle.svm.core.jdk.RuntimeSupport;

public class VirtualInvokeProfilerHook {

    public static class StartupHook implements RuntimeSupport.Hook {
        @Override
        public void execute(boolean isFirstIsolate) {
            VirtualInvokeProfiler.enableProfiling();
        }
    }

    public static class ShutdownHook implements RuntimeSupport.Hook {
        @Override
        public void execute(boolean isFirstIsolate) {
            VirtualInvokeProfiler.dumpProfileData();
        }
    }
}
