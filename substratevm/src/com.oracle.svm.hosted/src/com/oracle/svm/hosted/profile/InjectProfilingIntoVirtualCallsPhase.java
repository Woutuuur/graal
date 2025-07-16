package com.oracle.svm.hosted.profile;

import com.oracle.svm.hosted.meta.HostedMethod;
import jdk.graal.compiler.bytecode.ResolvedJavaMethodBytecode;
import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.core.common.type.StampPair;
import jdk.graal.compiler.core.common.type.TypeReference;
import jdk.graal.compiler.nodes.CallTargetNode;
import jdk.graal.compiler.nodes.CallTargetNode.InvokeKind;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.EndNode;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.Invokable;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.InvokeNode;
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
import java.util.Set;
import java.util.stream.StreamSupport;

import static com.oracle.svm.util.ReflectionUtil.lookupMethod;

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
            0,
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
        return invoke;
    }

    @Override
    protected void run(StructuredGraph graph, HighTierContext context) {
        Set<Invoke> handledInvokes = new HashSet<>();

        StreamSupport.stream(graph.getInvokes().spliterator(), false)
            .filter(InjectProfilingIntoVirtualCallsPhase::shouldProfileInvoke)
            .filter(o -> !handledInvokes.contains(o))
            .forEach(invokeNode -> {
                HostedMethod targetMethod = (HostedMethod) invokeNode.getTargetMethod();

                if (false) {
                    targetMethod.wrapped.collectMethodImplementations(false)
                        .stream()
                        .findAny()
                        .ifPresentOrElse(implementation -> {
                            Stamp returnStamp = invokeNode.callTarget().returnStamp().getTrustedStamp();

                            ValueNode receiver = invokeNode.getReceiver();

                            ResolvedJavaMethod foo = context.getMetaAccess().lookupJavaMethod(lookupMethod(VirtualInvokeProfiler.class, "foo"));
                            ResolvedJavaMethod bar = context.getMetaAccess().lookupJavaMethod(lookupMethod(VirtualInvokeProfiler.class, "bar"));

                            MergeNode mergeNode = graph.add(new MergeNode());

                            FixedNode originalNext = invokeNode.next();
                            invokeNode.setNext(null);
                            mergeNode.setNext(originalNext);

                            InvokeNode invokeToFoo = createInvokeToMethod(foo, graph);
                            InvokeNode invokeToBar = createInvokeToMethod(bar, graph);

                            ValueNode[] args = invokeNode.callTarget().arguments().toArray(ValueNode.EMPTY_ARRAY); // .subList(0, invokeNode.callTarget().arguments().size() - 1).toArray(ValueNode.EMPTY_ARRAY);
                            InvokeNode invokeToImplementation = createInvokeToMethod(
                                    context.getMetaAccess().lookupJavaMethod(implementation.getJavaMethod()),
                                    graph,
                                    args,
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
                                System.out.println("PHI " + phi);
                                phi.addInput(invokeToImplementation);
                                phi.addInput(invokeNode.asNode());
                            }

                            IfNode ifInstanceOfCheck = graph.addOrUnique(
                                new IfNode(
                                    graph.addOrUnique(InstanceOfNode.create(TypeReference.createExactTrusted(context.getMetaAccess().lookupJavaType(implementation.getDeclaringClass().getJavaClass())), receiver)),
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
                } else {
                    String callSiteSource = getSource(invokeNode);
                    ConstantNode callSiteSourceConstant = ConstantNode.forConstant(context.getConstantReflection().forString(callSiteSource), context.getMetaAccess());

                    String targetMethodName = invokeNode.callTarget().targetName();
                    ConstantNode targetMethodNameConstant = ConstantNode.forConstant(context.getConstantReflection().forString(targetMethodName), context.getMetaAccess());


                    CallSiteProfilerNode callSiteProfilerNode = graph.add(new CallSiteProfilerNode(
                            graph.addOrUnique(callSiteSourceConstant),
                            graph.addOrUnique(targetMethodNameConstant),
                            invokeNode.callTarget().arguments().getFirst(),
                            graph.addOrUnique(ConstantNode.forInt(CallSiteRegistry.allocateId()))
                    ));
                    graph.addBeforeFixed(invokeNode.asFixedNode(), callSiteProfilerNode);
                }
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
