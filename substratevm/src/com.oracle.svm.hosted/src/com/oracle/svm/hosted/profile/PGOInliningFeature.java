package com.oracle.svm.hosted.profile;

import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.jdk.RuntimeSupport;
import com.oracle.svm.core.option.HostedOptionKey;
import com.oracle.svm.hosted.FeatureImpl;
import jdk.graal.compiler.java.BytecodeParser;
import jdk.graal.compiler.java.CallSiteProfile;
import jdk.graal.compiler.options.Option;
import jdk.graal.compiler.options.OptionType;
import jdk.graal.compiler.phases.tiers.Suites;
import jdk.graal.compiler.phases.util.Providers;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.hosted.RuntimeReflection;
import org.graalvm.nativeimage.impl.InternalPlatform;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static jdk.graal.compiler.java.BytecodeParser.methodSignature;

@AutomaticallyRegisteredFeature
@Platforms(InternalPlatform.NATIVE_ONLY.class)
public class PGOInliningFeature implements InternalFeature  {

    private final static float INLINE_PROFILES_PERCENTAGE = 0.2f;
    private static List<CallSiteProfile> callSiteProfiles = new ArrayList<>();
    private static Set<CallSiteProfile> callSiteProfilesToInline = null;

    public static Set<CallSiteProfile> getCallSiteProfilesToInline() {
        return callSiteProfilesToInline;
    }

    public static boolean performPGOBasedInlining() {
        return callSiteProfilesToInline != null;
    }

    static {
        String profileDataDumpFileName = PGOInliningFeature.Options.ProfileDataDumpFileName.getValue();
        if (profileDataDumpFileName != null) {
            Path jsonFilePath = Path.of(profileDataDumpFileName);
            try {
                String json = Files.readString(jsonFilePath);
                callSiteProfiles = CallSiteProfile.loadFromJSON(json).stream().sorted().toList();

                int indexLimit = Math.round(INLINE_PROFILES_PERCENTAGE * callSiteProfiles.size());
                callSiteProfilesToInline = new HashSet<>(callSiteProfiles.subList(0, indexLimit));

                BytecodeParser.callSiteProfilesToInline = callSiteProfilesToInline;

                System.out.println("Loaded " + callSiteProfiles.size() + " call sites profiles from file: " + jsonFilePath);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static class Options {

        // @formatter:off
        @Option(help = "File containing profiling data for PGO based inlining", type = OptionType.User)
        public static final HostedOptionKey<String> ProfileDataDumpFileName = new HostedOptionKey<>(null);
        // @formatter:on
    }

    @Override
    public void duringSetup(DuringSetupAccess access) {
        FeatureImpl.DuringSetupAccessImpl accessImpl = (FeatureImpl.DuringSetupAccessImpl) access;

        callSiteProfilesToInline.stream()
            .filter(CallSiteProfile::isIndirectCall)
            .forEach(profile -> {
                try {
                    Class<?> clazz = access.findClassByName(profile.getTargetClassName());

                    System.out.println("Looking for method for call site profile: " + profile.getTargetMethod());
                    Stream.of(clazz.getDeclaredMethods(), clazz.getMethods()).flatMap(Stream::of)
                        .distinct()
                        .filter(m -> methodSignature(m).equals(profile.getTargetMethod()))
                        .findFirst().ifPresentOrElse(m -> {
                            accessImpl.findSubclasses(clazz).stream()
                                .filter(subClass -> profile.receiverCounts.containsKey(subClass.getName()))
                                .forEach(subClass -> {
                                    try {
                                        Method concreteMethod = subClass.getMethod(m.getName(), m.getParameterTypes());
                                        profile.receiverNameConcreteMethods.put(subClass.getName(), concreteMethod);
                                    } catch (NoSuchMethodException e) {
                                        System.out.println("No method found for " + m.getName() + " " + Arrays.toString(m.getParameterTypes()));
                                    }
                                });
                        }, () -> System.out.println("No method found for call site profile: " + profile.getTargetMethod()));

                    System.out.println();
                } catch (Exception e) {
                    System.out.println("ERROR: " + e.getMessage());
                }
            });

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
