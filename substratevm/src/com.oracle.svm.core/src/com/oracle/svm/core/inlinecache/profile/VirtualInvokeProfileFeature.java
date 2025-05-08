package com.oracle.svm.core.inlinecache.profile;

import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
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
            Method m = VirtualInvokeProfiler.class.getDeclaredMethod("profileVirtualInvoke", Object.class, int.class);
            Method m2 = VirtualInvokeProfiler.class.getDeclaredMethod("enableProfiling");
            RuntimeReflection.register(m, m2);
        } catch (NoSuchMethodException ex) {
            throw new RuntimeException("Failed to register method for reflection: " + ex.getMessage(), ex);
        }
        RuntimeReflection.register(VirtualInvokeProfiler.class);
    }

    @Override
    public void registerGraalPhases(Providers providers, Suites suites, boolean hosted) {
        if (Boolean.getBoolean("enableVirtualInvokeProfilingPhase")) {
            suites.getHighTier().prependPhase(new InjectProfilingIntoVirtualCallsPhase());
        }
    }

}
