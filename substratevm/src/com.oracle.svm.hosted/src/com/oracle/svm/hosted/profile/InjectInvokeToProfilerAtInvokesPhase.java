package com.oracle.svm.hosted.profile;

import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.Invokable;
import jdk.graal.compiler.nodes.Invoke;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.tiers.HighTierContext;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.LineNumberTable;
import jdk.vm.ci.meta.ResolvedJavaMethod;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.StreamSupport;

public class InjectInvokeToProfilerAtInvokesPhase extends BasePhase<HighTierContext> {

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

    private static boolean shouldProfileInvoke(Invoke invoke) {
        String fullyQualifiedName = invoke.callTarget().targetMethod().format("%H.%n(%p)");
        String contextMethod = invoke.getContextMethod().toString();

        return Arrays.stream(EXCLUDED_PACKAGES).noneMatch(fullyQualifiedName::startsWith) &&
               Arrays.stream(EXCLUDED_CONTEXT_METHOD_FUZZY_PARTS).noneMatch(contextMethod::contains);
    }

    @Override
    protected void run(StructuredGraph graph, HighTierContext context) {
        Set<Invoke> handledInvokes = new HashSet<>();

        StreamSupport.stream(graph.getInvokes().spliterator(), false)
            .filter(InjectInvokeToProfilerAtInvokesPhase::shouldProfileInvoke)
            .filter(o -> !handledInvokes.contains(o))
            .forEach(invokeNode -> {
                String callSiteSource = getSource(invokeNode);
                ConstantNode callSiteSourceConstant = ConstantNode.forConstant(context.getConstantReflection().forString(callSiteSource), context.getMetaAccess());

                String targetMethodName = invokeNode.callTarget().targetMethod().format("%H.%n(%p):%r");
                ConstantNode targetMethodNameConstant = ConstantNode.forConstant(context.getConstantReflection().forString(targetMethodName), context.getMetaAccess());

                ConstantNode isDirectConstant = ConstantNode.forConstant(JavaConstant.forBoolean(invokeNode.callTarget().invokeKind().isDirect()), context.getMetaAccess());

                ValueNode receiver = invokeNode.callTarget().invokeKind().isDirect() ?
                    graph.addOrUnique(ConstantNode.forConstant(context.getConstantReflection().forString(invokeNode.callTarget().targetMethod().getDeclaringClass().toJavaName()), context.getMetaAccess())) :
                    invokeNode.callTarget().arguments().getFirst();

                CallSiteProfilerNode callSiteProfilerNode = graph.add(new CallSiteProfilerNode(
                    graph.addOrUnique(isDirectConstant),
                    graph.addOrUnique(callSiteSourceConstant),
                    graph.addOrUnique(targetMethodNameConstant),
                    receiver,
                    graph.addOrUnique(ConstantNode.forInt(CallSiteRegistry.allocateId()))
                ));
                graph.addBeforeFixed(invokeNode.asFixedNode(), callSiteProfilerNode);
                handledInvokes.add(invokeNode);
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
