package com.oracle.svm.core.inlinecache.profile;

import jdk.graal.compiler.nodes.CallTargetNode.InvokeKind;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.tiers.HighTierContext;
import jdk.vm.ci.meta.ResolvedJavaType;

import java.util.Arrays;
import java.util.stream.StreamSupport;

public class InjectProfilingIntoVirtualCallsPhase extends BasePhase<HighTierContext> {

    private static final String[] EXCLUDED_PACKAGES = {
        "com.oracle.svm.core",
    };

    private static boolean shouldProfileInvoke(Invoke invoke) {
        InvokeKind kind = invoke.getInvokeKind();
        String fullyQualifiedName = invoke.callTarget().targetMethod().format("%H.%n(%p)");

        return kind.isIndirect() && Arrays.stream(EXCLUDED_PACKAGES).noneMatch(fullyQualifiedName::startsWith) &&
                !invoke.getContextMethod().toString().contains("VirtualInvokeProfiler");
    }

    @Override
    protected void run(StructuredGraph graph, HighTierContext context) {
        StreamSupport.stream(graph.getInvokes().spliterator(), false)
            .filter(InjectProfilingIntoVirtualCallsPhase::shouldProfileInvoke)
            .forEach(invoke -> {
                ResolvedJavaType declaringClass = invoke.getContextMethod().getDeclaringClass();

                String sourceOrigin = null;
                if (invoke.getContextMethod().getLineNumberTable() != null) {
                    sourceOrigin = declaringClass.toJavaName() + "." + invoke.getTargetMethod().getName() + "(" + declaringClass.getSourceFileName() + ":" + invoke.getContextMethod().getLineNumberTable().getLineNumber(invoke.bci()) + ")";
                } else {
                    System.out.println("WARNING: No line number table for " + declaringClass.toJavaName() + "." + invoke.getTargetMethod().getName() + " (" + declaringClass.getSourceFileName() + ")");
                }

                ConstantNode sourceOriginConstant = ConstantNode.forConstant(context.getConstantReflection().forString(sourceOrigin), context.getMetaAccess());
                CallSiteProfilerNode callSiteProfilerNode = graph.add(new CallSiteProfilerNode(
                    graph.addOrUnique(sourceOriginConstant),
                    invoke.callTarget().arguments().getFirst(),
                    graph.addOrUnique(ConstantNode.forInt(CallSiteRegistry.allocateId()))
                ));
                graph.addBeforeFixed(invoke.asFixedNode(), callSiteProfilerNode);
            });
    }

}
