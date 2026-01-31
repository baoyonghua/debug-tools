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
package io.github.future0923.debug.tools.attach;

import io.github.future0923.debug.tools.base.config.AgentArgs;
import io.github.future0923.debug.tools.base.constants.ProjectConstants;
import io.github.future0923.debug.tools.base.hutool.core.io.FileUtil;
import io.github.future0923.debug.tools.base.logging.Logger;
import io.github.future0923.debug.tools.base.utils.DebugToolsExecUtils;
import io.github.future0923.debug.tools.base.utils.DebugToolsFileUtils;
import io.github.future0923.debug.tools.base.utils.HotswapIgnoreStaticFieldUtils;
import io.github.future0923.debug.tools.hotswap.core.HotswapAgent;
import io.github.future0923.debug.tools.server.DebugToolsBootstrap;
import io.github.future0923.debug.tools.sql.SqlPrintByteCodeEnhance;
import io.github.future0923.debug.tools.vm.JvmToolsUtils;
import javassist.CtClass;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Objects;

/**
 * debug-tools-agent
 *
 * @author future0923
 */
public class DebugToolsAttach {

    private static final Logger logger = Logger.getLogger(DebugToolsAttach.class);

    /**
     * Agent 启动入口
     *
     * @param agentArgs 参数: server=true,tcp-port=12345,http-port=22222,...
     * @param inst      instrumentation
     * @throws Exception 启动失败
     */
    public static void premain(String agentArgs, Instrumentation inst) throws Exception {
        // 解析参数
        AgentArgs parse = AgentArgs.parse(agentArgs);
        if (parse.getLogLevel() != null) {
            Logger.setLevel(parse.getLogLevel());
        }

        String javaHome = System.getProperty("java.home");
        logger.info("JAVA_HOME:{}", javaHome);

        // 从 java.home 中加载 tools.jar，并将 tools.jar 的路径放入到 系统类加载器 中, 确保在运行时能够访问到 tools.jar 中的类
        loadToolsJar(javaHome);

        if (ProjectConstants.DEBUG) {
            // 开启javassist debug
            CtClass.debugDump = "debug/javassist";
            // 开启cglib debug
            System.setProperty("cglib.debugLocation", "debug/cglib");
        }

        // 【核心】完成对 JVM工具 的初始化操作
        JvmToolsUtils.init();

        // 对 jdbc 进行增强以便于拦截SQL进行打印
        SqlPrintByteCodeEnhance.enhance(inst, parse);

        if (Objects.equals(parse.getHotswap(), "true")) {
            // fixed(hotswap):静态常量在运行时重新赋值时, 会被热加载被清空赋值(#185)
            // 例如: 一个静态常量 map 在被修改时, 静态常量 map 的值会被清空
            // 因此在这里通过配置的方式来让用户手动指定应该忽略哪些类的静态常量
            HotswapIgnoreStaticFieldUtils.create(parse.getIgnoreStaticFieldPath());
            HotswapAgent.init(parse, inst);
        }

        // 【核心】启动 debug-tools-server
        if (Objects.equals(parse.getServer(), "true")) {
            startServer(parse, inst);
        }

        // 自动 attach 本地应用
        if (Objects.equals(parse.getAutoAttach(), "true")) {
            autoAttach();
        }
    }

    /**
     * Attach agent 入口
     *
     * @param agentArgs 参数
     * @param inst      instrumentation
     * @throws Exception 启动失败
     */
    public static void agentmain(String agentArgs, Instrumentation inst) throws Exception {
        startServer(AgentArgs.parse(agentArgs), inst);
    }

    /**
     * 如果能找到 tools.jar 则载入
     *
     * @param javaHome java home
     */
    private static void loadToolsJar(String javaHome) {
        try {
            Class.forName("com.sun.tools.javac.processing.JavacProcessingEnvironment");
        } catch (ClassNotFoundException e) {
            File toolsJar;
            try {
                toolsJar = DebugToolsExecUtils.findToolsJar(javaHome);
            } catch (Exception ee) {
                // 小于等于8找不到时给提示
                logger.warning("The tools.jar file was not found, so remote dynamic compilation cannot be used. " +
                        "If you want to use remote dynamic compilation, " +
                        "please only use the jdk environment, not the jre. {}", ee.getMessage());
                return;
            }

            if (toolsJar != null) {
                try {
                    // 获取系统类加载器<AppClassLoader>， 它是 URLClassLoader 类型
                    URLClassLoader sysLoader = (URLClassLoader) ClassLoader.getSystemClassLoader();

                    // 通过反射获取 URLClassLoader 的 addURL 方法，并设置方法可访问(protected)
                    Method addURL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
                    addURL.setAccessible(true);

                    // 调用 addURL 方法，将 tools.jar 添加到系统类加载器的 classpath 中
                    // 这样就可以在运行时动态加载 tools.jar 中的类
                    addURL.invoke(sysLoader, toolsJar.toURI().toURL());

                    logger.info("Loaded tools.jar file in {}", sysLoader.getClass().getName());
                } catch (Exception ex) {
                    // 如果加载 toos.jar 失败，记录警告信息。这会导致远程动态编译功能不可用
                    logger.warning("Failed to load the tools.jar file, so remote dynamic compilation cannot be used. " +
                            "If you want to use remote dynamic compilation, " +
                            "please only use the jdk environment, not the jre. {}", ex.getMessage());
                }
            }
        }
    }

    /**
     * 启动 server 服务
     *
     * @param agentArgs 参数
     * @param inst      instrumentation
     */
    private static void startServer(AgentArgs agentArgs, Instrumentation inst) {
        DebugToolsBootstrap.getInstance(inst).start(agentArgs);
    }

    private static void autoAttach() throws IOException {
        FileUtil.writeUtf8String("1", DebugToolsFileUtils.getAutoAttachFile());
    }
}