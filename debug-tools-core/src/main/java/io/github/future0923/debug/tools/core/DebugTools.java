/*
 * Copyright (C) 2024-2025 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.future0923.debug.tools.core;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import io.github.future0923.debug.tools.base.config.AgentArgs;
import io.github.future0923.debug.tools.base.enums.ArgType;
import io.github.future0923.debug.tools.base.logging.AnsiLog;
import io.github.future0923.debug.tools.base.utils.DebugToolsIOUtils;
import io.github.future0923.debug.tools.base.utils.DebugToolsJavaVersionUtils;
import org.apache.commons.cli.*;

import java.io.IOException;
import java.util.Properties;

/**
 * DebugTools 核心启动类
 *
 * @author future0923
 */
public class DebugTools {

    /**
     * java [-Xbootclasspath/a:tools.jar] -jar debug-tools-core.jar --pid 1234 --agent debug-tools-agent.jar --tcp-port 12345 --http-port 22222
     *
     * @param args
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {
        // 解析命令行参数
        DefaultParser parser = new DefaultParser();
        Options options = new Options();
        options.addOption("p", "pid", true, "java pid");
        options.addOption("a", "agent", true, "java agent path");
        options.addOption(ArgType.TCP_PORT.getOpt(), ArgType.TCP_PORT.getLongOpt(), ArgType.TCP_PORT.isHasArg(), ArgType.TCP_PORT.getDescription());
        options.addOption(ArgType.HTTP_PORT.getOpt(), ArgType.HTTP_PORT.getLongOpt(), ArgType.HTTP_PORT.isHasArg(), ArgType.HTTP_PORT.getDescription());
        CommandLine cmd;
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("debug-tools", options);
            return;
        }

        // 尝试 Attach 到 目标JVM
        attachAgent(cmd);
    }

    /**
     * 将 debug-tools-agent.jar attach 到目标 JVM 进程
     * <p>
     * 该方法的主要功能：
     * 1. 从命令行参数中获取目标 JVM 的进程 ID 和 agent jar 包路径
     * 2. 通过 VirtualMachine API 查找并附加到目标 JVM 进程
     * 3. 检查目标 JVM 和当前 JVM 的 Java 版本兼容性
     * 4. 配置 agent 参数（TCP端口和HTTP端口）
     * 5. 加载 debug-tools-agent.jar 到目标 JVM 中，详见: {@link io.github.future0923.debug.tools.attach.DebugToolsAttach}
     * 6. 处理附加过程中可能出现的版本不匹配等异常情况
     * </p>
     *
     * @param cmd java [-Xbootclasspath/a:tools.jar] -jar debug-tools-core.jar --pid 1234 --agent debug-tools-agent.jar --tcp-port 12345 --http-port 22222
     *            命令行参数对象，包含以下参数：
     *            --pid: 目标 JVM 的进程 ID
     *            --agent: debug-tools-agent.jar 的路径
     *            --tcp-port: TCP 服务端口（可选，默认为 12345）
     *            --http-port: HTTP 服务端口（可选，默认为 22222）
     * @throws Exception 当附加失败时抛出异常，可能的原因包括：
     *                   - 目标进程不存在
     *                   - 没有足够的权限附加到目标进程
     *                   - agent jar 文件不存在或无效
     *                   - JVM 版本不兼容
     */
    private static void attachAgent(CommandLine cmd) throws Exception {
        // 目标JVM 进程ID
        String javaPid = cmd.getOptionValue("pid");

        // agent: debug-tools-agent.jar
        String debugToolsAgentPath = cmd.getOptionValue("agent");

        // 根据 pid 来找到 目标JVM
        VirtualMachineDescriptor virtualMachineDescriptor = null;
        for (VirtualMachineDescriptor descriptor : VirtualMachine.list()) {
            String pid = descriptor.id();
            if (pid.equals(javaPid)) {
                virtualMachineDescriptor = descriptor;
                break;
            }
        }

        // 执行 Attach 操作
        VirtualMachine virtualMachine = null;
        try {
            if (null == virtualMachineDescriptor) {
                virtualMachine = VirtualMachine.attach(javaPid);
            } else {
                virtualMachine = VirtualMachine.attach(virtualMachineDescriptor);
            }

            Properties targetSystemProperties = virtualMachine.getSystemProperties();
            // 目标 JVM进程 的 Java版本
            String targetJavaVersion = DebugToolsJavaVersionUtils.javaVersionStr(targetSystemProperties);
            // 运行当前 JVM进程 的 Java版本
            String currentJavaVersion = DebugToolsJavaVersionUtils.javaVersionStr();
            if (targetJavaVersion != null && currentJavaVersion != null) {
                if (!targetJavaVersion.equals(currentJavaVersion)) {
                    AnsiLog.warn("Current VM java version: {} do not match target VM java version: {}, attach may fail.",
                            currentJavaVersion, targetJavaVersion);
                    AnsiLog.warn("Target VM JAVA_HOME is {}, debug-tools-boot JAVA_HOME is {}, try to set the same JAVA_HOME.",
                            targetSystemProperties.getProperty("java.home"), System.getProperty("java.home"));
                }
            }

            try {
                // args: server=true,tcp-port=12345,http-port=22222
                AgentArgs agentArgs = new AgentArgs();
                agentArgs.setServer(Boolean.TRUE.toString()); // 启动 debug-tools server
                agentArgs.setTcpPort(cmd.getOptionValue(ArgType.TCP_PORT.getLongOpt(), String.valueOf(DebugToolsIOUtils.getAvailablePort(12345))));
                agentArgs.setHttpPort(cmd.getOptionValue(ArgType.HTTP_PORT.getLongOpt(), String.valueOf(DebugToolsIOUtils.getAvailablePort(22222))));
                // attach成功后，加载 debug-tools-agent.jar
                virtualMachine.loadAgent(debugToolsAgentPath, agentArgs.format());
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().contains("Non-numeric value found")) {
                    // 低版本的 JDK 运行 debug-tools-agent.jar 可能会报 Non-numeric value found
                    // 此错误信息可以忽略，附加操作可能已经成功完成，系统仍会尝试进行连接。
                    AnsiLog.warn(e);
                    AnsiLog.warn("It seems to use the lower version of JDK to attach the higher version of JDK.");
                    AnsiLog.warn(
                            "This error message can be ignored, the attach may have been successful, and it will still try to connect.");
                } else {
                    throw e;
                }
            } catch (com.sun.tools.attach.AgentLoadException ex) {
                if ("0".equals(ex.getMessage())) {
                    // 高版本的 JDK 运行 debug-tools-agent.jar 可能会报 0 -> https://stackoverflow.com/a/54454418
                    // 此错误信息可以忽略，附加操作可能已经成功完成，系统仍会尝试进行连接。
                    AnsiLog.warn(ex);
                    AnsiLog.warn("It seems to use the higher version of JDK to attach the lower version of JDK.");
                    AnsiLog.warn(
                            "This error message can be ignored, the attach may have been successful, and it will still try to connect.");
                } else {
                    throw ex;
                }
            }
        } finally {
            if (null != virtualMachine) {
                virtualMachine.detach();
            }
        }
    }
}
