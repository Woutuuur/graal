package com.oracle.svm.hosted.profile;

import com.oracle.svm.hosted.meta.HostedMethod;
import jdk.graal.compiler.bytecode.ResolvedJavaMethodBytecode;
import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.core.common.type.StampPair;
import jdk.graal.compiler.core.common.type.TypeReference;
import jdk.graal.compiler.nodes.CallTargetNode;
import jdk.graal.compiler.nodes.CallTargetNode.InvokeKind;
import jdk.graal.compiler.nodes.EndNode;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.Invokable;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.InvokeNode;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.MergeNode;
import jdk.graal.compiler.nodes.PhiNode;
import jdk.graal.compiler.nodes.ProfileData;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.ValuePhiNode;
import jdk.graal.compiler.nodes.java.InstanceOfNode;
import jdk.graal.compiler.nodes.java.MethodCallTargetNode;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.tiers.HighTierContext;
import jdk.vm.ci.meta.LineNumberTable;
import jdk.vm.ci.meta.ResolvedJavaMethod;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

import static com.oracle.svm.util.ReflectionUtil.lookupMethod;

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

    private static boolean shouldInjectInlineCache(Invoke invoke) {
        InvokeKind kind = invoke.getInvokeKind();
        String fullyQualifiedName = invoke.callTarget().targetMethod().format("%H.%n(%p)");
        String contextMethod = invoke.getContextMethod().toString();

        return kind.equals(InvokeKind.Virtual) &&
            Arrays.stream(EXCLUDED_PACKAGES).noneMatch(fullyQualifiedName::startsWith) &&
            Arrays.stream(EXCLUDED_CONTEXT_METHOD_FUZZY_PARTS).noneMatch(contextMethod::contains);
    }

    private InvokeNode createInvokeToMethod(ResolvedJavaMethod method, StructuredGraph graph, StampPair stamp) {
        return createInvokeToMethod(method, graph, ValueNode.EMPTY_ARRAY, InvokeKind.Static, stamp);
    }

    private InvokeNode createInvokeToMethod(ResolvedJavaMethod method, StructuredGraph graph) {
        return createInvokeToMethod(method, graph, StampPair.createSingle(StampFactory.forVoid()));
    }

    private InvokeNode createInvokeToMethod(ResolvedJavaMethod method, StructuredGraph graph, ValueNode[] args, InvokeKind kind, StampPair returnStamp) {
        CallTargetNode callTargetNode = graph.add(new MethodCallTargetNode(
            kind,
            method,
            args,
            returnStamp,
            null
        ));
        InvokeNode invoke = graph.add(new InvokeNode(callTargetNode,0));
        ValueNode[] stack = ValueNode.EMPTY_ARRAY;
        if (!returnStamp.getTrustedStamp().equals(StampFactory.forVoid())) {
            stack = new ValueNode[]{invoke};
        }
        FrameState stateAfter = graph.add(new FrameState(
            null,
            new ResolvedJavaMethodBytecode(method),
            -1,
            ValueNode.EMPTY_ARRAY,
            stack,
            stack.length,
            null,
            null,
            ValueNode.EMPTY_ARRAY,
            null,
            FrameState.StackState.AfterPop
        ));
        invoke.setStateAfter(stateAfter);
        invoke.setPolymorphic(false);
        invoke.setUseForInlining(true);

        return invoke;
    }

    @Override
    protected void run(StructuredGraph graph, HighTierContext context) {
        Set<Invoke> handledInvokes = new HashSet<>();

        StreamSupport.stream(graph.getInvokes().spliterator(), false)
            .filter(VirtualInvokeInlineCachePhase::shouldInjectInlineCache)
            .filter(o -> !handledInvokes.contains(o))
            .forEach(invokeNode -> {
                HostedMethod targetMethod = (HostedMethod) invokeNode.getTargetMethod();

//                if (!invokeNode.getTargetMethod().getName().equals("foo")) {
//                    return;
//                }

                targetMethod.wrapped.collectMethodImplementations(false)
                    .stream()
                    .findAny()
                    .ifPresentOrElse(implementationMethod -> {
                        Stamp returnStamp = invokeNode.callTarget().returnStamp().getTrustedStamp();

                        ValueNode receiver = invokeNode.getReceiver();

                        ResolvedJavaMethod foo = context.getMetaAccess().lookupJavaMethod(lookupMethod(InvokeProfiler.class, "foo"));
                        ResolvedJavaMethod bar = context.getMetaAccess().lookupJavaMethod(lookupMethod(InvokeProfiler.class, "bar"));

                        MergeNode mergeNode = graph.add(new MergeNode());

                        FixedNode originalNext = invokeNode.next();
                        invokeNode.setNext(null);
                        mergeNode.setNext(originalNext);

                        InvokeNode invokeToFoo = createInvokeToMethod(foo, graph);
                        InvokeNode invokeToBar = createInvokeToMethod(bar, graph);

                        List<ValueNode> args = invokeNode.callTarget().arguments();
                        InvokeNode invokeToImplementation = createInvokeToMethod(
                            context.getMetaAccess().lookupJavaMethod(implementationMethod.getJavaMethod()),
                            graph,
                            args.toArray(ValueNode.EMPTY_ARRAY),
                            InvokeKind.Special,
                            StampPair.createSingle(returnStamp)
                        );
                        FixedWithNextNode predecessor = (FixedWithNextNode) invokeNode.predecessor();
                        predecessor.setNext(null);

                        invokeToFoo.setNext(invokeToImplementation);
                        invokeToBar.setNext(invokeNode.asFixedNode());

                        EndNode cachedBranchEnd = graph.add(new EndNode());
                        EndNode slowPathEnd = graph.add(new EndNode());
                        mergeNode.addForwardEnd(cachedBranchEnd);
                        mergeNode.addForwardEnd(slowPathEnd);

                        invokeToImplementation.setNext(cachedBranchEnd);
                        invokeNode.setNext(slowPathEnd);

                        if (!returnStamp.equals(StampFactory.forVoid())) {
                            PhiNode phi = graph.addOrUnique(new ValuePhiNode(returnStamp, mergeNode));
                            invokeNode.asNode().replaceAtUsages(phi, usage -> !(usage instanceof FrameState));
                            phi.addInput(invokeToImplementation);
                            phi.addInput(invokeNode.asNode());
                        }

                        LogicNode instanceOfNode = graph.addOrUnique(
                            InstanceOfNode.create(
                                TypeReference.createExactTrusted(context.getMetaAccess().lookupJavaType(implementationMethod.getDeclaringClass().getJavaClass())),
                                receiver
                            )
                        );

                        IfNode ifInstanceOfCheck = graph.addOrUnique(
                            new IfNode(
                                instanceOfNode,
                                invokeToFoo,
                                invokeToBar,
                                ProfileData.BranchProbabilityData.unknown()
                            )
                        );

                        predecessor.setNext(ifInstanceOfCheck);

                        handledInvokes.add(invokeNode);
                        handledInvokes.add(invokeToBar);
                        handledInvokes.add(invokeToFoo);
                        handledInvokes.add(invokeToImplementation);
                    }, () -> System.out.println("No implementation found for " + targetMethod));
            });
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
