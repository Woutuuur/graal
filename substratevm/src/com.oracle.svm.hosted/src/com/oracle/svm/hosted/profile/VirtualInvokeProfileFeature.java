package com.oracle.svm.hosted.profile;

import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.jdk.RuntimeSupport;
import com.oracle.svm.core.option.HostedOptionKey;
import jdk.graal.compiler.options.Option;
import jdk.graal.compiler.options.OptionType;
import jdk.graal.compiler.phases.tiers.Suites;
import jdk.graal.compiler.phases.util.Providers;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.hosted.RuntimeReflection;
import org.graalvm.nativeimage.impl.InternalPlatform;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

@AutomaticallyRegisteredFeature
@Platforms(InternalPlatform.NATIVE_ONLY.class)
public class VirtualInvokeProfileFeature implements InternalFeature  {

    public static class Options {

        // @formatter:off
        @Option(help = "File containing profiling data for PGO based inlining", type = OptionType.User)
        public static final HostedOptionKey<String> ProfileDataDumpFileName = new HostedOptionKey<>(null);
        // @formatter:on
    }

    @Override
    public void duringSetup(DuringSetupAccess access) {
        try {
            RuntimeSupport.getRuntimeSupport().addStartupHook(isFirstIsolate -> VirtualInvokeProfiler.enableProfiling());
            RuntimeSupport.getRuntimeSupport().addShutdownHook(isFirstIsolate -> VirtualInvokeProfiler.dumpProfileData());

            Method m = VirtualInvokeProfiler.class.getDeclaredMethod("profileVirtualInvoke", boolean.class, String.class, String.class, Object.class, int.class);
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
        }
    }

}
