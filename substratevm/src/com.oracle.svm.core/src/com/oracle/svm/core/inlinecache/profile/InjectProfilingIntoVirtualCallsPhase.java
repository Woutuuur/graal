package com.oracle.svm.core.inlinecache.profile;

import jdk.graal.compiler.bytecode.ResolvedJavaMethodBytecode;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.core.common.type.StampPair;
import jdk.graal.compiler.nodes.CallTargetNode;
import jdk.graal.compiler.nodes.CallTargetNode.InvokeKind;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.InvokeNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.java.MethodCallTargetNode;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.tiers.HighTierContext;
import jdk.vm.ci.meta.ResolvedJavaMethod;

import java.util.Arrays;
import java.util.stream.StreamSupport;

import static com.oracle.svm.util.ReflectionUtil.lookupMethod;

public class InjectProfilingIntoVirtualCallsPhase extends BasePhase<HighTierContext> {

    private static final String[] EXCLUDED_PACKAGES = {
            "com.oracle.svm.core",
    };

    private void createInvokeToMethod(StructuredGraph graph, ResolvedJavaMethod method) {
        CallTargetNode profilerEnableCallTargetNode = graph.add(new MethodCallTargetNode(
                InvokeKind.Static,
                method,
                ValueNode.EMPTY_ARRAY,
                StampPair.createSingle(StampFactory.forVoid()),
                null
        ));

        InvokeNode invokeToProfilerEnable = graph.add(new InvokeNode(
                profilerEnableCallTargetNode,
                0
        ));

        FrameState stateAfter = graph.add(new FrameState(
                null,
                new ResolvedJavaMethodBytecode(method),
                0,
                ValueNode.EMPTY_ARRAY,
                ValueNode.EMPTY_ARRAY,
                0,
                null,
                null,
                ValueNode.EMPTY_ARRAY,
                null,
                FrameState.StackState.AfterPop
        ));

        invokeToProfilerEnable.setStateAfter(stateAfter);
        graph.addAfterFixed(graph.start(), invokeToProfilerEnable);
    }

    private static boolean shouldProfileInvoke(Invoke invoke) {
        InvokeKind kind = invoke.getInvokeKind();
        String fullyQualifiedName = invoke.callTarget().targetMethod().format("%H.%n(%p)");
        return kind.isIndirect() && Arrays.stream(EXCLUDED_PACKAGES).noneMatch(fullyQualifiedName::startsWith);
    }

    @Override
    protected void run(StructuredGraph graph, HighTierContext context) {

        if (graph.method().getName().equals("main")) {
            ResolvedJavaMethod method = context.getMetaAccess().lookupJavaMethod(lookupMethod(VirtualInvokeProfiler.class, "enableProfiling"));

            createInvokeToMethod(graph, method);
        }

        StreamSupport.stream(graph.getInvokes().spliterator(), true)
                .filter(InjectProfilingIntoVirtualCallsPhase::shouldProfileInvoke)
                .forEach(invoke -> {
                    CallSiteProfilerNode callSiteProfilerNode = graph.add(new CallSiteProfilerNode(
                            invoke.callTarget().arguments().getFirst(),
                            graph.unique(ConstantNode.forInt(CallSiteRegistry.allocateId()))
                    ));
                    graph.addBeforeFixed(invoke.asFixedNode(), callSiteProfilerNode);
                });
    }

}
