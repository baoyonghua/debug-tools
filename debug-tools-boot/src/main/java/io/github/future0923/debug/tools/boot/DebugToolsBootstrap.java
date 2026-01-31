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
package io.github.future0923.debug.tools.boot;

import io.github.future0923.debug.tools.base.enums.ArgType;
import io.github.future0923.debug.tools.base.hutool.core.io.FileUtil;
import io.github.future0923.debug.tools.base.hutool.core.util.StrUtil;
import io.github.future0923.debug.tools.base.logging.AnsiLog;
import io.github.future0923.debug.tools.base.utils.*;
import io.github.future0923.debug.tools.core.DebugTools;
import org.apache.commons.cli.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.InputMismatchException;
import java.util.List;

/**
 * DebugTools 远程 Debug 入口
 *
 * @author future0923
 */
public class DebugToolsBootstrap {

    /**
     * DebugTools 主入口方法
     * <p>
     * 主要功能：
     * 1. 解析命令行参数（目标进程PID、TCP端口、HTTP端口）
     * 2. 从lib目录下获取debug-tools-core.jar和debug-tools-agent.jar
     * 3. 根据JDK版本处理tools.jar的加载（JDK8及以下需要将tools.jar添加到bootclasspath）
     * 4. 构建并启动新的Java进程执行 debug-tools-core.jar，执行入口详见: {@link DebugTools}
     * 5. 将子进程的标准输出和错误输出重定向到当前进程
     * 6. 等待attach操作完成并输出结果
     * </p>
     *
     * @param args 命令行参数，支持以下选项：
     *             --pid: 目标Java进程的PID
     *             --tcp-port: TCP通信端口（默认12345）
     *             --http-port: HTTP服务端口（默认22222）
     */
    public static void main(String[] args) {
        // 解析参数 --> java -jar debug-tools-agent.jar --pid 1234  --tcp-port 1234 --http-port 1234
        DefaultParser parser = new DefaultParser();
        Options options = new Options();
        options.addOption(ArgType.TCP_PORT.getOpt(), ArgType.TCP_PORT.getLongOpt(), ArgType.TCP_PORT.isHasArg(), ArgType.TCP_PORT.getDescription());
        options.addOption(ArgType.HTTP_PORT.getOpt(), ArgType.HTTP_PORT.getLongOpt(), ArgType.HTTP_PORT.isHasArg(), ArgType.HTTP_PORT.getDescription());
        options.addOption(ArgType.PROCESS_ID.getOpt(), ArgType.PROCESS_ID.getLongOpt(), ArgType.PROCESS_ID.isHasArg(), ArgType.PROCESS_ID.getDescription());
        CommandLine cmd;
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("debug-tools", options);
            return;
        }

        printDebugToolsVersion();

        /*
        debug-tools-boot项目打包完成后, 在其lib目录下放置 debug-tools-core.jar 和 debug-tools-agent.jar 这两个核心的jar文件。
        因此在这里的操作就是从 lib目录 下获取这两个jar文件来完成命令行参数的拼接：
            - java -jar debug-tools-core.jar --pid 1234 --agent debug-tools-agent.jar --tcp-port 12345 --http-port 22222
        然后通过 ProcessBuilder 启动一个新的 Java 进程来执行 debug-tools-core.jar，
        并传递必要的参数（如目标进程的PID、agent jar路径、TCP端口和HTTP端口等）。

         */
        File debugToolsHomeDir = DebugToolsLibUtils.getDebugToolsLibDir();
        File coreJarFile = new File(debugToolsHomeDir, "debug-tools-core.jar");
        FileUtil.del(coreJarFile);
        coreJarFile = DebugToolsFileUtils.getLibResourceJar(DebugToolsBootstrap.class.getClassLoader(), "debug-tools-core");

        File agentJarFile = new File(debugToolsHomeDir, "debug-tools-agent.jar");
        FileUtil.del(agentJarFile);
        agentJarFile = DebugToolsFileUtils.getLibResourceJar(DebugToolsBootstrap.class.getClassLoader(), "debug-tools-agent");

        long pid = getPidFromCmdOrSelectByUser(cmd);

        AnsiLog.info("Try to attach process " + pid);
        String javaHome = DebugToolsExecUtils.findJavaHome();
        File javaPath = DebugToolsExecUtils.findJava(javaHome);
        if (javaPath == null) {
            throw new IllegalArgumentException(
                    "Can not find java/java.exe executable file under java home: " + javaHome);
        }

        // 尝试在jdk的lib目录下查找 tools.jar
        // 在 JDK 8 及更早版本中，tools.jar 包含了 Attach API 相关的类（如 VirtualMachine），
        // 这些类用于实现进程间的Attach功能，允许一个 Java 进程附加到另一个正在运行的 Java 进程上。
        // 因此在 JDK 8 中，需要将 tools.jar 添加到 bootclasspath 中才能使用这些 API。
        //
        // 从 JDK 9 开始，Java 引入了模块化系统（JPMS - Java Platform Module System），
        // Attach API 相关的类被移到了 jdk.attach 模块中，不再需要单独的 tools.jar 文件。
        // 这些类已经内置在 JDK 中，可以直接使用，因此 JDK 9 及以上版本不再需要 tools.jar。
        File toolsJar = DebugToolsExecUtils.findToolsJar(javaHome);
        if (DebugToolsJavaVersionUtils.isLessThanJava9()) {
            if (toolsJar == null || !toolsJar.exists()) {
                throw new IllegalArgumentException("Can not find tools.jar under java home: " + javaHome);
            }
        }

        List<String> command = new ArrayList<>();
        command.add(javaPath.getAbsolutePath());  // java

        /*
        -Xbootclasspath/a: 参数的作用是将指定的 jar 文件追加（append）到 bootclasspath 的末尾。
        如果是 JDK 8 及更早版本，需要将 tools.jar 添加到 bootclasspath 中，
            - bootclasspath: 启动类路径, 启动类加载器<Bootstrap ClassLoader> 会从 bootclasspath 指定的路径中加载类，
                             在默认情况下只会加载JDK核心类库(如: rt.jar)
        这是因为 Attach API 相关的类（如 com.sun.tools.attach.VirtualMachine）在 JDK8 中会存放在 tools.jar 下，
        因此在我们的应用程序中无法直接访问这些类，因为这些类并不会被任何类加载器所加载到。
        所以为了在 JDK 8 中能够使用 Attach API，我们需要将 tools.jar 添加到 bootclasspath 中，
        从而让 启动类加载器 可以从 tools.jar 中加载 Attach API 相关的类。
        如果不将 tools.jar 添加到 bootclasspath，这些类将无法被启动类加载器找到和加载，导致程序运行时抛出 ClassNotFoundException。
         */
        if (toolsJar != null && toolsJar.exists()) {
            command.add("-Xbootclasspath/a:" + toolsJar.getAbsolutePath());
        }

        // java -Xbootclasspath/a:tools.jar -jar debug-tools-core.jar --pid 1234 --agent debug-tools-agent.jar --tcp-port 12345 --http-port 22222
        command.add("-jar");
        command.add(coreJarFile.getAbsolutePath());
        command.add("--pid");
        command.add(String.valueOf(pid));
        command.add("--agent");
        command.add(agentJarFile.getAbsolutePath());
        String tcpPort = cmd.getOptionValue(ArgType.TCP_PORT.getLongOpt(), String.valueOf(DebugToolsIOUtils.getAvailablePort(12345)));
        command.add("--" + ArgType.TCP_PORT.getLongOpt());
        command.add(tcpPort);
        String httpPort = cmd.getOptionValue(ArgType.HTTP_PORT.getLongOpt(), String.valueOf(DebugToolsIOUtils.getAvailablePort(22222)));
        command.add("--" + ArgType.HTTP_PORT.getLongOpt());
        command.add(httpPort);

        // 启动一个新的 Java 进程来执行 debug-tools-core.jar 来完成对 目标JVM 的 Attach
        ProcessBuilder pb = new ProcessBuilder(command);
        try {
            // 启动新进程
            final Process proc = pb.start();

            // 创建线程将子进程的 标准输出流 重定向到 当前进程的标准输出，这样可以实时查看 debug-tools-core.jar 的运行日志
            Thread redirectStdout = new Thread(() -> {
                InputStream inputStream = proc.getInputStream();
                try {
                    // 将子进程的标准输出复制到当前进程的标准输出
                    DebugToolsIOUtils.copy(inputStream, System.out);
                } catch (IOException e) {
                    // 发生异常时关闭输入流
                    DebugToolsIOUtils.close(inputStream);
                }
            });

            // 创建线程将子进程的 错误输出流 重定向到 当前进程的错误输出，这样可以实时查看 debug-tools-core.jar 的错误信息
            Thread redirectStderr = new Thread(() -> {
                InputStream inputStream = proc.getErrorStream();
                try {
                    // 将子进程的错误输出复制到当前进程的错误输出
                    DebugToolsIOUtils.copy(inputStream, System.err);
                } catch (IOException e) {
                    // 发生异常时关闭输入流
                    DebugToolsIOUtils.close(inputStream);
                }
            });

            // 启动两个重定向线程
            redirectStdout.start();
            redirectStderr.start();

            // 等待两个重定向线程执行完毕 -> 确保所有输出都被正确重定向后再继续执行
            redirectStdout.join();
            redirectStderr.join();

            // 获取子进程的退出码, exitValue() 会阻塞等待进程结束并返回退出码 -> 退出码为 0 表示成功，非 0 表示失败
            int exitValue = proc.exitValue();
            if (exitValue != 0) {
                AnsiLog.error("attach fail, targetPid: " + pid);  // 如果退出码非 0，说明 attach 失败
                System.exit(1);
            }
        } catch (Throwable e) {
            // 捕获所有异常但不处理
            // 这里忽略异常是因为即使启动失败，后续的日志会提示用户 attach 成功与否
        }

        AnsiLog.info("Attach process {} success. tcp port {}, http port {}.", pid, tcpPort, httpPort);
    }

    private static long getPidFromCmdOrSelectByUser(CommandLine cmd) {
        long pid = -1;
        String pidArg = cmd.getOptionValue(ArgType.PROCESS_ID.getLongOpt());
        if (StrUtil.isNotBlank(pidArg)) {
            pid = Long.parseLong(cmd.getOptionValue(ArgType.PROCESS_ID.getLongOpt()));
        }
        if (pid < 0) {
            try {
                pid = DebugToolsExecUtils.select();
            } catch (InputMismatchException e) {
                AnsiLog.error("Please input an integer to select pid.");
                System.exit(1);
            }
        }
        if (pid < 0) {
            AnsiLog.error("Please select an available pid.");
            System.exit(1);
        }
        return pid;
    }

    private static void printDebugToolsVersion() {
        // 打印版本信息
        Package bootstrapPackage = DebugToolsBootstrap.class.getPackage();
        if (bootstrapPackage != null) {
            String debugToolsBootVersion = bootstrapPackage.getImplementationVersion();
            if (debugToolsBootVersion != null) {
                AnsiLog.info("debug-tools-boot version: " + debugToolsBootVersion);
            }
        }
    }

}
