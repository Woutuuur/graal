package com.oracle.svm.core.inlinecache.profile;

import jdk.graal.compiler.bytecode.ResolvedJavaMethodBytecode;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.core.common.type.StampPair;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.CallTargetNode;
import jdk.graal.compiler.nodes.CallTargetNode.InvokeKind;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.InvokeNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.java.MethodCallTargetNode;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaMethod;

import java.lang.reflect.Method;
import java.util.Map;

import static com.oracle.svm.util.ReflectionUtil.lookupMethod;
import static jdk.graal.compiler.nodeinfo.InputType.Value;

@NodeInfo(nameTemplate = "ProfiledCallSite#{p#receiver/s}")
public class CallSiteProfilerNode extends FixedWithNextNode implements Lowerable {
    public static final NodeClass<CallSiteProfilerNode> TYPE = NodeClass.create(CallSiteProfilerNode.class);

    @Input private ValueNode receiver;
    @Input(Value) private ValueNode callSiteIdNode;

    private ProfilingMethod profilingMethod;

    public CallSiteProfilerNode(ValueNode receiver, ValueNode callSiteIdNode) {
        super(TYPE, StampFactory.forVoid());
        this.receiver = receiver;
        this.callSiteIdNode = callSiteIdNode;
    }

    @Override
    public Map<Object, Object> getDebugProperties(Map<Object, Object> map) {
        map.put("receiver", receiver);
        return super.getDebugProperties(map);
    }

    @Override
    public void lower(LoweringTool tool) {
        StructuredGraph graph = graph();

        if (profilingMethod == null) {
            profilingMethod =  new ProfilingMethod(tool.getMetaAccess());
        }

        ValueNode[] args = {receiver, callSiteIdNode};
        CallTargetNode profilerCallTargetNode = graph.add(new MethodCallTargetNode(
                InvokeKind.Static,
                profilingMethod.getMethod(),
                args,
                StampPair.createSingle(StampFactory.forVoid()),
                null
        ));

        InvokeNode invokeToProfiler = graph.add(new InvokeNode(profilerCallTargetNode,0));

        FrameState stateAfter = graph.add(new FrameState(
                null,
                profilingMethod.getByteCode(),
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

        invokeToProfiler.setStateAfter(stateAfter);
        graph.replaceFixed(this, invokeToProfiler);
    }

    static class ProfilingMethod {
        private final ResolvedJavaMethod method;
        private final ResolvedJavaMethodBytecode byteCode;

        public ProfilingMethod(MetaAccessProvider metaAccess) {
            Method profilingMethod = lookupMethod(VirtualInvokeProfiler.class, "profileVirtualInvoke", Object.class, int.class);

            this.method = metaAccess.lookupJavaMethod(profilingMethod);
            this.byteCode = new ResolvedJavaMethodBytecode(method);
        }

        public ResolvedJavaMethod getMethod() {
            return method;
        }

        public ResolvedJavaMethodBytecode getByteCode() {
            return byteCode;
        }
    }

}
