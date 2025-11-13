package jdk.graal.compiler.phases;

import jdk.graal.compiler.debug.TTY;
import jdk.graal.compiler.loop.phases.LoopFullUnrollPhase;
import jdk.graal.compiler.loop.phases.LoopPeelingPhase;
import jdk.graal.compiler.loop.phases.LoopUnswitchingPhase;
import jdk.graal.compiler.nodes.GraphState;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.phases.common.BoxNodeIdentityPhase;
import jdk.graal.compiler.phases.common.BoxNodeOptimizationPhase;
import jdk.graal.compiler.phases.common.CanonicalizerPhase;
import jdk.graal.compiler.phases.common.DeadCodeEliminationPhase;
import jdk.graal.compiler.phases.common.IncrementalCanonicalizerPhase;
import jdk.graal.compiler.phases.common.InitMemoryVerificationPhase;
import jdk.graal.compiler.phases.common.IterativeConditionalEliminationPhase;
import jdk.graal.compiler.virtual.phases.ea.FinalPartialEscapePhase;
import jdk.graal.compiler.virtual.phases.ea.ReadEliminationPhase;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class PhasePGO {

    private boolean compilerProfiling = false;
    private boolean useCompilerPGO = false;

    // [TODO] Don't hardcode the file name
    private static final File DUMP_FILE = new File("skippable_phases.txt");

    public void init() {
        compilerProfiling = Boolean.getBoolean("profileCompiler");
        useCompilerPGO = Boolean.getBoolean("useCompilerPGO");

        TTY.println("Compiler PGO is %s, compiler profiling is %s", useCompilerPGO ? "enabled" : "disabled", compilerProfiling ? "enabled" : "disabled");
        if (DUMP_FILE.exists() && !compilerProfiling && useCompilerPGO) {
            try {
                TTY.println("Compiler PGO enabled, reading skippable phases from %s", DUMP_FILE);
                readFingerprintsFromFile();
                TTY.println("Found %d skippable phases.", numberOfSkippablePhases());
            } catch (Throwable t) {
                TTY.println("Error reading skippable_phases.txt: %s", t);
            }
        } else if (compilerProfiling) {
            try {
                if (DUMP_FILE.exists()) {
                    boolean deleted = DUMP_FILE.delete();
                    if (!deleted) {
                        throw new IOException("Could not delete existing file " + DUMP_FILE.getAbsolutePath());
                    }
                }
                boolean created = DUMP_FILE.createNewFile();
                if (!created) {
                    throw new IOException("Could not create file " + DUMP_FILE.getAbsolutePath());
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void readFingerprintsFromFile() {
        if (!DUMP_FILE.exists()) {
            throw new RuntimeException("PGO data file does not exist: " + DUMP_FILE.getAbsolutePath());
        }

        Path skippablePhasesPath = DUMP_FILE.toPath();

        try {
            Files.readAllLines(skippablePhasesPath)
                    .stream()
                    .map(Integer::valueOf)
                    .forEach(skippablePhaseFingerprints::add);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private final Set<Class<?>> whitelistedPhases = Set.of(
        DeadCodeEliminationPhase.class,
        CanonicalizerPhase.class,
        LoopFullUnrollPhase.class,
        LoopPeelingPhase.class,
        IncrementalCanonicalizerPhase.class,
        LoopUnswitchingPhase.class,
        BoxNodeIdentityPhase.class,
        BoxNodeOptimizationPhase.class,
        ReadEliminationPhase.class,
        IterativeConditionalEliminationPhase.class,
        InitMemoryVerificationPhase.class,
        FinalPartialEscapePhase.class
    );

    final private Set<Integer> skippablePhaseFingerprints = ConcurrentHashMap.newKeySet();

    public int numberOfSkippablePhases() {
        return skippablePhaseFingerprints.size();
    }

    public boolean canSkipPhase(BasePhase<?> phase, StructuredGraph graph) {
        return useCompilerPGO &&
            whitelistedPhases.stream().anyMatch(cls -> cls.equals(phase.getClass())) &&
            !phase.mustApply(graph.getGraphState()) &&
            skippablePhaseFingerprints.contains(computeGraphFingerprint(graph, phase));
    }

    public boolean shouldProfilePhase(BasePhase<?> phase) {
        return compilerProfiling && whitelistedPhases.stream().anyMatch(cls -> cls.equals(phase.getClass()));
    }

    public void recordSkippablePhase(BasePhase<?> phase, StructuredGraph graph) {
        skippablePhaseFingerprints.add(computeGraphFingerprint(graph, phase));
    }

    private int computeGraphFingerprint(StructuredGraph graph, BasePhase<?> phase) {
        int h = 1;

        h = 31 * h + graph.method().format("%H.%n(%p):%r").hashCode();
        h = 31 * h + phase.contractorName().hashCode();

        // [TODO] Check if stage flags are necessary
        GraphState gs = graph.getGraphState();
        for (GraphState.StageFlag f : gs.getStageFlags()) {
            h = 31 * h + f.ordinal();
        }

        for (String c : graph.getNodes().stream().map(n -> n.getNodeClass().shortName()).toList()) {
            h = 31 * h + c.hashCode();
        }

        return h;
    }

    public boolean shouldDumpPGOData() {
        return compilerProfiling;
    }

    public void dumpToFile() {
        Path skippablePhasesPath = DUMP_FILE.toPath();

        try {
            Files.write(
                skippablePhasesPath,
                skippablePhaseFingerprints.stream().map(Object::toString).collect(Collectors.toList())
            );
        } catch (IOException e) {
            TTY.println("Failed to write PGO data to file: " + e.getMessage());
        }
    }

    private static final PhasePGO INSTANCE = new PhasePGO();

    private PhasePGO() {}

    public static PhasePGO getInstance() {
        return INSTANCE;
    }

}
