package cn.lypi.boot.tool;

import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.runtime.Executor;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.tool.BlockingPermissionGate;
import cn.lypi.tool.DefaultToolRuntime;
import cn.lypi.tool.EventPublishingPermissionGate;
import cn.lypi.tool.PermissionGate;
import cn.lypi.tool.PermissionPromptPort;
import cn.lypi.tool.PermissionResponseGate;
import cn.lypi.tool.ToolRuntimeOptions;
import cn.lypi.tool.builtin.BuiltInTools;
import cn.lypi.tool.shell.HostExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class LyPiToolAutoConfiguration {
    /**
     * 创建默认宿主机命令执行器。
     */
    @Bean
    @ConditionalOnMissingBean(Executor.class)
    public Executor hostExecutor() {
        return new HostExecutor();
    }

    /**
     * 创建工具运行时。
     *
     * NOTE: 缺少本地权限 prompt 时保持 fail-safe deny；存在 prompt 和事件总线时，
     * 使用事件装饰 gate 发布权限请求和决策事件。
     */
    @Bean
    @ConditionalOnMissingBean(ToolRuntimePort.class)
    public ToolRuntimePort toolRuntime(
        SecurityRuntimePort securityRuntime,
        Executor executor,
        ObjectProvider<EventBus> eventBus,
        ObjectProvider<PermissionResponseGate> responseGate,
        ObjectProvider<PermissionPromptPort> promptPort
    ) {
        EventBus resolvedEventBus = eventBus.getIfAvailable();
        DefaultToolRuntime runtime = toolRuntime(
            resolvedEventBus,
            new cn.lypi.tool.DefaultToolRegistry(),
            new cn.lypi.tool.ToolSchemaValidator(),
            new cn.lypi.tool.ToolExecutionPlanner(),
            new cn.lypi.tool.ToolResultBudgeter(),
            new cn.lypi.tool.ToolRuntimeContextFactory(ToolRuntimeOptions.defaults()),
            cn.lypi.tool.ToolExecutionInterceptors.noop(),
            securityRuntime,
            responseGate.getIfAvailable(),
            promptPort.getIfAvailable()
        );
        BuiltInTools.registerDefaults(runtime, executor);
        return runtime;
    }

    /**
     * 创建事件总线权限响应 gate。
     */
    @Bean
    @ConditionalOnBean(EventBus.class)
    @ConditionalOnMissingBean({PermissionResponseGate.class, PermissionPromptPort.class})
    public PermissionResponseGate permissionResponseGate(EventBus eventBus) {
        return new EventBusPermissionResponseGate(eventBus);
    }

    private DefaultToolRuntime toolRuntime(
        EventBus eventBus,
        cn.lypi.tool.DefaultToolRegistry registry,
        cn.lypi.tool.ToolSchemaValidator schemaValidator,
        cn.lypi.tool.ToolExecutionPlanner executionPlanner,
        cn.lypi.tool.ToolResultBudgeter resultBudgeter,
        cn.lypi.tool.ToolRuntimeContextFactory contextFactory,
        cn.lypi.tool.ToolExecutionInterceptor interceptor,
        SecurityRuntimePort securityRuntime,
        PermissionResponseGate responseGate,
        PermissionPromptPort promptPort
    ) {
        if (eventBus != null && responseGate != null) {
            return new DefaultToolRuntime(
                registry,
                schemaValidator,
                executionPlanner,
                resultBudgeter,
                contextFactory,
                interceptor,
                securityRuntime,
                responseGate,
                eventBus
            );
        }
        return new DefaultToolRuntime(
            registry,
            schemaValidator,
            executionPlanner,
            resultBudgeter,
            contextFactory,
            interceptor,
            securityRuntime,
            permissionGate(eventBus, promptPort),
            eventBus
        );
    }

    private PermissionGate permissionGate(EventBus eventBus, PermissionPromptPort promptPort) {
        if (promptPort == null) {
            return PermissionGate.denying();
        }
        PermissionGate blockingGate = new BlockingPermissionGate(promptPort);
        if (eventBus == null) {
            return blockingGate;
        }
        return new EventPublishingPermissionGate(eventBus, blockingGate);
    }
}
