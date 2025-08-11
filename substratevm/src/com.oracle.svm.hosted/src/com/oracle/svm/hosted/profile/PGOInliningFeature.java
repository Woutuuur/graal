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

@AutomaticallyRegisteredFeature
@Platforms(InternalPlatform.NATIVE_ONLY.class)
public class PGOInliningFeature implements InternalFeature  {

    public static class Options {

        // @formatter:off
        @Option(help = "File containing profiling data for PGO based inlining", type = OptionType.User)
        public static final HostedOptionKey<String> ProfileDataDumpFileName = new HostedOptionKey<>(null);
        // @formatter:on
    }

    @Override
    public void duringSetup(DuringSetupAccess access) {
        try {
            RuntimeReflection.register(InvokeProfiler.class);

            if (!Boolean.getBoolean("disableVirtualInvokeProfilingPhase")) {
                RuntimeReflection.register(InvokeProfiler.class.getDeclaredMethod("profileVirtualInvoke", boolean.class, String.class, String.class, Object.class, int.class));
                RuntimeSupport.getRuntimeSupport().addStartupHook(isFirstIsolate -> InvokeProfiler.enableProfiling());
                RuntimeSupport.getRuntimeSupport().addShutdownHook(isFirstIsolate -> InvokeProfiler.dumpProfileData());

                RuntimeReflection.register(InvokeProfiler.class.getDeclaredMethod("enableProfiling"));
            } else if (!Boolean.getBoolean("disableVirtualInlineCachePhase")) {
                Method foo = InvokeProfiler.class.getDeclaredMethod("foo");
                Method bar = InvokeProfiler.class.getDeclaredMethod("bar");

                RuntimeReflection.register(foo, bar);
            }

        } catch (NoSuchMethodException ex) {
            throw new RuntimeException("Failed to register method for reflection: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void registerGraalPhases(Providers providers, Suites suites, boolean hosted) {
        if (!Boolean.getBoolean("disableVirtualInvokeProfilingPhase")) {
            System.out.println("Injecting virtual invoke profiling phase");
            suites.getHighTier().prependPhase(new InjectInvokeToProfilerAtInvokesPhase());
        }
        // Mutually exclusive; can't do the virtual inline caching without profiling data or profiling enabled.
        else if (!Boolean.getBoolean("disableVirtualInlineCachePhase") && Options.ProfileDataDumpFileName.getValue() != null) {
            System.out.println("Injecting inline virtual invoke cache phase");
            suites.getHighTier().prependPhase(new VirtualInvokeInlineCachePhase());
        }
    }

}
