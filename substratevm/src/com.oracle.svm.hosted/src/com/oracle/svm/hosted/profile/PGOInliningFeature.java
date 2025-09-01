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

import static jdk.graal.compiler.java.BytecodeParser.methodShortSignature;

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
        return !Boolean.getBoolean("disableInlineCachePhase") &&
            Options.ProfileDataDumpFileName.getValue() != null &&
            callSiteProfilesToInline != null;
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

    private void determineConcreteMethodsForProfiledInvokes(FeatureImpl.DuringSetupAccessImpl access) {
        callSiteProfilesToInline.stream()
            .filter(CallSiteProfile::isIndirectCall)
            .forEach(profile -> {
                profile.getReceiverCounts().keySet().forEach(receiver -> {
                    Class<?> subClass = access.findClassByName(receiver);

                    if (subClass == null) {
                        return;
                    }

                    Arrays.stream(subClass.getDeclaredMethods())
                        .filter(m -> {
                            String shortSignatureTargetMethod = profile.targetMethod.substring(profile.targetMethod.lastIndexOf('.') + 1);
                            return methodShortSignature(m).equals(shortSignatureTargetMethod);
                        })
                        .forEach(m -> {
                            System.out.println("Concrete method: " + m + " for receiver: " + receiver + " for profile: " + profile.getTargetMethod());
                            profile.receiverNameConcreteMethods.put(subClass.getName(), m);
                        });
                });
            });
    }

    @Override
    public void duringSetup(DuringSetupAccess access) {
        if (callSiteProfilesToInline != null) {
            determineConcreteMethodsForProfiledInvokes((FeatureImpl.DuringSetupAccessImpl) access);
        }

        try {
            RuntimeReflection.register(InvokeProfiler.class);
            RuntimeReflection.register(CallSiteProfile.class);

            if (!Boolean.getBoolean("disableVirtualInvokeProfilingPhase")) {
                RuntimeReflection.register(InvokeProfiler.class.getDeclaredMethod("profileVirtualInvoke", boolean.class, String.class, String.class, Object.class, int.class));
                RuntimeSupport.getRuntimeSupport().addStartupHook(isFirstIsolate -> InvokeProfiler.enableProfiling());
                RuntimeSupport.getRuntimeSupport().addShutdownHook(isFirstIsolate -> InvokeProfiler.dumpProfileData());

                RuntimeReflection.register(InvokeProfiler.class.getDeclaredMethod("enableProfiling"));
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
    }
}
