package com.oracle.svm.hosted.profile;

import com.oracle.svm.core.meta.SharedMethod;
import com.oracle.svm.core.thread.Safepoint;
import com.oracle.svm.hosted.meta.HostedMethod;
import jdk.graal.compiler.bytecode.ResolvedJavaMethodBytecode;
import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.core.common.type.TypeReference;
import jdk.graal.compiler.core.common.type.VoidStamp;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.nodes.AbstractBeginNode;
import jdk.graal.compiler.nodes.BeginNode;
import jdk.graal.compiler.nodes.CallTargetNode;
import jdk.graal.compiler.nodes.CallTargetNode.InvokeKind;
import jdk.graal.compiler.nodes.DeoptimizingNode;
import jdk.graal.compiler.nodes.DirectCallTargetNode;
import jdk.graal.compiler.nodes.EndNode;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.FullInfopointNode;
import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.Invokable;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.InvokeNode;
import jdk.graal.compiler.nodes.InvokeWithExceptionNode;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.MergeNode;
import jdk.graal.compiler.nodes.PhiNode;
import jdk.graal.compiler.nodes.ProfileData;
import jdk.graal.compiler.nodes.SafepointNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.ValuePhiNode;
import jdk.graal.compiler.nodes.java.InstanceOfNode;
import jdk.graal.compiler.nodes.java.MethodCallTargetNode;
import jdk.graal.compiler.nodes.java.TypeSwitchNode;
import jdk.graal.compiler.nodes.util.GraphUtil;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.common.CanonicalizerPhase;
import jdk.graal.compiler.phases.tiers.HighTierContext;
import jdk.graal.compiler.truffle.host.HostInliningPhase;
import jdk.vm.ci.meta.JavaTypeProfile;
import jdk.vm.ci.meta.LineNumberTable;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.TriState;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

public class VirtualInvokeInlineCachePhase extends BasePhase<HighTierContext> {

    private static final String[] EXCLUDED_PACKAGES = {
        "com.oracle.svm.core",
        "com.oracle.svm.hosted.profile",
        "java.util",
        "jdk.internal",
        "java.lang.Object",
        "java.lang.System",
        "java.lang.Runtime",
        "java.lang.Thread"
    };

    private static final String[] EXCLUDED_CONTEXT_METHOD_FUZZY_PARTS = {
        "VirtualInvokeProfiler",
        "CallSiteProfile",
        "NoAllocationVerifier",
        "HashMap"
    };

    static Set<String> handledInvokes = new HashSet<>(List.of("."));

    private static String getInvokeKey(Invoke invoke) {
        return invoke.stateAfter() == null ? "." : invoke.getContextMethod().format("%H.%n(%p)") + "->" + invoke.callTarget().targetMethod().format("%H.%n(%p)");
    }

    private static void markHandled(Invoke invoke) {
        handledInvokes.add(getInvokeKey(invoke));
    }

    private static boolean isHandled(Invoke invoke) {
        return handledInvokes.contains(getInvokeKey(invoke));
    }

    private static boolean shouldInjectInlineCache(Invoke invoke) {
        if (isHandled(invoke)) {
            return false;
        }

        InvokeKind kind = invoke.getInvokeKind();
        String fullyQualifiedName = invoke.callTarget().targetMethod().format("%H.%n(%p)");
        String contextMethod = invoke.getContextMethod().toString();

        return kind.equals(InvokeKind.Virtual) &&
            Arrays.stream(EXCLUDED_PACKAGES).noneMatch(fullyQualifiedName::startsWith) &&
            Arrays.stream(EXCLUDED_CONTEXT_METHOD_FUZZY_PARTS).noneMatch(contextMethod::contains);
    }



    @Override
    protected void run(StructuredGraph graph, HighTierContext context) {
//        StreamSupport.stream(graph.getInvokes().spliterator(), false)
//            .filter(VirtualInvokeInlineCachePhase::shouldInjectInlineCache)
//            .forEach(invokeNode -> {
//                HostedMethod targetMethod = (HostedMethod) invokeNode.getTargetMethod();
//
//                if (!invokeNode.getTargetMethod().getName().equals("foo")) {
//                    return;
//                }
//
//                targetMethod.wrapped.collectMethodImplementations(false)
//                    .stream()
//                    .findAny()
//                    .ifPresentOrElse(implementationMethod -> {
//                        Stamp returnStamp = invokeNode.callTarget().returnStamp().getTrustedStamp();
//                        ValueNode receiver = invokeNode.getReceiver();
//                        FixedWithNextNode predecessor = (FixedWithNextNode) invokeNode.predecessor();
//                        FixedNode successor = invokeNode.next();
//
//                        invokeNode.setNext(null);
//                        MergeNode mergeNode = graph.add(new MergeNode());
//                        mergeNode.setNext(successor);
//
//                        List<ValueNode> args = invokeNode.callTarget().arguments();
//                        MethodCallTargetNode oldCallTarget = (MethodCallTargetNode) invokeNode.callTarget();
//                        CallTargetNode callTargetNode = graph.add(new DirectCallTargetNode(
//                            args.toArray(ValueNode.EMPTY_ARRAY),
//                            oldCallTarget.returnStamp(),
//                            oldCallTarget.targetMethod().getSignature().toParameterTypes(invokeNode.getReceiverType()),
//                            context.getMetaAccess().lookupJavaMethod(implementationMethod.getJavaMethod()),
//                            ((SharedMethod) oldCallTarget.targetMethod()).getCallingConventionKind().toType(true),
//                            InvokeKind.Special
//                        ));
//                        InvokeNode invokeToImplementation = graph.addOrUnique(new InvokeNode(callTargetNode, invokeNode.bci()));
//                        predecessor.setNext(null);
//
//                        EndNode cachedBranchEnd = graph.add(new EndNode());
//                        EndNode slowPathEnd = graph.add(new EndNode());
//                        mergeNode.addForwardEnd(cachedBranchEnd);
//                        mergeNode.addForwardEnd(slowPathEnd);
//
//                        invokeToImplementation.setNext(cachedBranchEnd);
//                        invokeNode.setNext(slowPathEnd);
//
//                        if (!returnStamp.equals(StampFactory.forVoid())) {
//                            PhiNode phi = graph.addOrUnique(new ValuePhiNode(returnStamp, mergeNode));
//                            for (Node node : invokeNode.asNode().usages()) {
//                                System.out.println("Usage: " + node);
//                            }
//                            invokeNode.asNode().replaceAtUsages(phi, usage -> !usage.equals(invokeNode.stateAfter()) && !usage.equals(invokeNode.stateDuring()));
//                            phi.addInput(invokeToImplementation);
//                            phi.addInput(invokeNode.asNode());
//                        }
//
//                        LogicNode instanceOfNode = graph.addOrUnique(
//                            InstanceOfNode.create(
//                                TypeReference.createExactTrusted(context.getMetaAccess().lookupJavaType(implementationMethod.getDeclaringClass().getJavaClass())),
//                                receiver
//                            )
//                        );
//                        IfNode ifInstanceOfCheck = graph.add(
//                            new IfNode(
//                                instanceOfNode,
//                                invokeToImplementation,
//                                invokeNode.asFixedNode(),
//                                ProfileData.BranchProbabilityData.unknown()
//                            )
//                        );
//
//                        predecessor.setNext(ifInstanceOfCheck);
//                        FrameState originalStateAfter = invokeNode.stateAfter();
//
//                        if (originalStateAfter != null) {
////                            ValueNode[] stack = new ValueNode[originalStateAfter.stackSize()];
////                            for (int i = 0; i < originalStateAfter.stackSize(); i++) {
////                                stack[i] = originalStateAfter.stackAt(i);
////                            }
////                            FrameState stateAfter = graph.add(new FrameState(
////                                originalStateAfter.outerFrameState(),
////                                new ResolvedJavaMethodBytecode(invokeNode.getContextMethod()),
////                                invokeToImplementation.bci(),
////                                ValueNode.EMPTY_ARRAY,
////                                stack,
////                                originalStateAfter.stackSize(),
////                                null,
////                                ValueNode.EMPTY_ARRAY,
////                                ValueNode.EMPTY_ARRAY,
////                                originalStateAfter.monitorIds(),
////                                FrameState.StackState.BeforePop
////                            ));
//                            invokeToImplementation.setStateAfter(graph.addOrUnique(originalStateAfter.duplicateModifiedDuringCall(invokeToImplementation.bci(), invokeToImplementation.asNode().getStackKind())));
//                        }
//                        if (invokeNode.stateDuring() != null) {
//                            invokeToImplementation.setStateDuring(graph.addOrUnique(invokeNode.stateDuring().duplicateWithVirtualState()));
//                        }
//
//                        markHandled(invokeNode);
//                        markHandled(invokeToImplementation);
//                        System.out.println("Injected inline cache for " + getInvokeKey(invokeNode) + " at " + getSource(invokeNode));
//                    }, () -> System.out.println("No implementation found for " + targetMethod));
//            });
    }

    private static String getSource(Invokable invokeNode) {
        String sourceOrigin = null;

        ResolvedJavaMethod callerMethod = invokeNode.getContextMethod();

        try {
            LineNumberTable lineNumberTable = callerMethod.getLineNumberTable();

            if (lineNumberTable != null) {
                int lineNumber = lineNumberTable.getLineNumber(invokeNode.bci());
                String fileName = callerMethod.getDeclaringClass().getSourceFileName();

                sourceOrigin = String.format("%s:%d", fileName, lineNumber);
            }
        } catch (Throwable ignored) {
            sourceOrigin = "Unknown";
        }

        return sourceOrigin;
    }

}
