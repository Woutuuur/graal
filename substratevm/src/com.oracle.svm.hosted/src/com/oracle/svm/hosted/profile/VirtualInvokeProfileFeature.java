package com.oracle.svm.hosted.profile;

import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.jdk.RuntimeSupport;
import jdk.graal.compiler.phases.tiers.Suites;
import jdk.graal.compiler.phases.util.Providers;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.hosted.RuntimeReflection;
import org.graalvm.nativeimage.impl.InternalPlatform;

import java.lang.reflect.Method;

@AutomaticallyRegisteredFeature
@Platforms(InternalPlatform.NATIVE_ONLY.class)
public class VirtualInvokeProfileFeature implements InternalFeature  {

    @Override
    public void duringSetup(DuringSetupAccess access) {
        try {
            Method m = VirtualInvokeProfiler.class.getDeclaredMethod("profileVirtualInvoke", String.class, Object.class, int.class);
            Method m2 = VirtualInvokeProfiler.class.getDeclaredMethod("enableProfiling");
            Method foo = VirtualInvokeProfiler.class.getDeclaredMethod("foo");
            Method bar = VirtualInvokeProfiler.class.getDeclaredMethod("bar");
            RuntimeReflection.register(m, m2, foo, bar);
        } catch (NoSuchMethodException ex) {
            throw new RuntimeException("Failed to register method for reflection: " + ex.getMessage(), ex);
        }
        RuntimeReflection.register(VirtualInvokeProfiler.class);
    }

    @Override
    public void registerGraalPhases(Providers providers, Suites suites, boolean hosted) {
        if (!Boolean.getBoolean("disableVirtualInvokeProfilingPhase")) {
            suites.getHighTier().prependPhase(new InjectProfilingIntoVirtualCallsPhase());

            RuntimeSupport.getRuntimeSupport().addStartupHook(isFirstIsolate -> VirtualInvokeProfiler.enableProfiling());
            RuntimeSupport.getRuntimeSupport().addShutdownHook(isFirstIsolate -> VirtualInvokeProfiler.dumpProfileData());
            RuntimeSupport.getRuntimeSupport().addShutdownHook(isFirstIsolate -> {
                try {
                    Class<?> clasz = Class.forName("org.h2.result.SearchRow");
                    System.out.println("Methods of SearchRow:");
                    for (var m : clasz.getFields()) {
                        System.out.println(m);
                    }
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException("Failed to register class for reflection: " + e.getMessage(), e);
                }
            });
        }
    }

}
