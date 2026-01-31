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
package io.github.future0923.debug.tools.server.scoket.handler;

import io.github.future0923.debug.tools.base.exception.DefaultClassLoaderException;
import io.github.future0923.debug.tools.base.hutool.core.exceptions.ExceptionUtil;
import io.github.future0923.debug.tools.base.logging.Logger;
import io.github.future0923.debug.tools.base.utils.DebugToolsFileUtils;
import io.github.future0923.debug.tools.base.utils.DebugToolsOSUtils;
import io.github.future0923.debug.tools.common.handler.BasePacketHandler;
import io.github.future0923.debug.tools.common.protocal.packet.Packet;
import io.github.future0923.debug.tools.common.protocal.packet.response.HotDeployResponsePacket;
import io.github.future0923.debug.tools.hotswap.core.config.PluginConfiguration;
import io.github.future0923.debug.tools.hotswap.core.config.PluginManager;
import io.github.future0923.debug.tools.hotswap.core.util.classloader.ClassLoaderHelper;
import io.github.future0923.debug.tools.server.DebugToolsBootstrap;

import java.io.File;
import java.io.OutputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 热部署请求处理器抽象类
 * <p>
 * 该抽象类提供了热部署的核心处理逻辑，实现了<b>动态替换运行中的Java类定义</b>的功能。
 * 通过Java Instrumentation API的{@code redefineClasses}方法，在不重启应用的情况下，
 * 将新的类字节码替换掉JVM中已加载的类定义，实现秒级热部署/热重载。
 * <p>
 * <b>工作原理：</b>
 * <ol>
 * <li>接收客户端发送的热部署请求数据包（包含类的新字节码）</li>
 * <li>通过子类实现的{@link #getByteCodes(Packet)}方法获取最新的字节码</li>
 * <li>通过子类实现的{@link #getClassLoader(Packet)}方法获取目标类加载器</li>
 * <li>将新字节码写入到extraClasspath配置的目录中（保存到磁盘）</li>
 * <li>检查要替换的类是否已经被类加载器加载</li>
 * <li>使用Instrumentation API的redefineClasses方法替换类定义</li>
 * <li>返回热部署结果给客户端</li>
 * </ol>
 * <p>
 * <b>子类实现：</b>
 * <ul>
 * <li>{@link LocalCompilerHotDeployRequestHandler} - 处理本地编译的字节码热部署</li>
 * <li>{@link RemoteCompilerHotDeployRequestHandler} - 处理远程编译的源码热部署</li>
 * </ul>
 * <p>
 * <b>线程安全：</b>
 * 使用{@link #hotswapLock}同步锁保证redefineClasses操作的原子性，
 * 防止并发热部署导致的类定义冲突。
 * <p>
 * <b>注意事项：</b>
 * <ul>
 * <li>只能替换已经被加载的类，未加载的类会被忽略</li>
 * <li>需要在启动时配置 extraClasspath路径 用于保存新字节码</li>
 * <li>所有异常都会被捕获并返回错误响应，不会导致服务崩溃</li>
 * </ul>
 *
 * @param <T> 热部署请求数据包类型，必须继承自{@link Packet}
 * @author future0923
 * @see java.lang.instrument.Instrumentation#redefineClasses(ClassDefinition...)
 * @see LocalCompilerHotDeployRequestHandler
 * @see RemoteCompilerHotDeployRequestHandler
 */
public abstract class AbstractHotDeployRequestHandler<T extends Packet> extends BasePacketHandler<T> {

    private static final Logger logger = Logger.getLogger(AbstractHotDeployRequestHandler.class);

    /**
     * Instrumentation 的 redefineClasses同步锁
     * <p>
     * 用于保证{@link Instrumentation#redefineClasses(ClassDefinition...)}操作的原子性，
     * 防止并发热部署导致的类定义冲突和不一致性问题。
     * <p>
     * <b>为什么需要同步：</b>
     * <ul>
     * <li>redefineClasses是非线程安全的操作</li>
     * <li>并发替换同一个类可能导致未定义行为</li>
     * <li>多个相互依赖的类需要原子性替换</li>
     * </ul>
     */
    protected final Object hotswapLock = new Object();

    /**
     * 获取要热部署的类字节码集合
     * <p>
     * 子类必须实现此方法，从请求数据包中提取出所有需要热部署的类字节码。
     * <p>
     * <b>不同子类的实现：</b>
     * <ul>
     * <li>{@link LocalCompilerHotDeployRequestHandler}：直接从数据包中获取已编译好的字节码</li>
     * <li>{@link RemoteCompilerHotDeployRequestHandler}：先编译源代码，再返回字节码</li>
     * </ul>
     *
     * @param packet 热部署请求数据包
     * @return 类名到字节码的映射表，Key为类的全限定名（如com.example.UserService），Value为类的字节码
     * @throws DefaultClassLoaderException 当类加载器异常时抛出
     */
    protected abstract Map<String, byte[]> getByteCodes(T packet) throws DefaultClassLoaderException;

    /**
     * 获取目标类加载器
     * <p>
     * 子类必须实现此方法，根据请求数据包中的类加载器标识符，获取对应的类加载器实例。
     * 这对于多类加载器环境（如Spring Boot）非常重要，确保类被加载到正确的类加载器中。
     * <p>
     * <b>为什么需要指定类加载器：</b>
     * <ul>
     * <li>在多模块应用中，不同模块的类由不同的类加载器加载</li>
     * <li>同一个类名可能在不同的类加载器中存在多个实例</li>
     * <li>只有使用正确的类加载器，才能替换到正确的类定义</li>
     * </ul>
     *
     * @param packet 热部署请求数据包
     * @return 目标类加载器实例
     * @throws DefaultClassLoaderException 当找不到对应的类加载器时抛出
     */
    protected abstract ClassLoader getClassLoader(T packet) throws DefaultClassLoaderException;

    /**
     * 处理热部署请求的核心方法
     * <p>
     * 执行完整的热部署流程，包括字节码获取、类加载器获取、文件写入、类定义替换等步骤。
     * <p>
     * <b>处理流程：</b>
     * <ol>
     * <li><b>获取字节码</b>：调用{@link #getByteCodes(Packet)}获取要部署的类字节码</li>
     * <li><b>获取类加载器</b>：调用{@link #getClassLoader(Packet)}获取目标类加载器</li>
     * <li><b>写入文件</b>：调用{@link #writeFile(ClassLoader, Map)}将字节码保存到extraClasspath目录下</li>
     * <li><b>构建ClassDefinition</b>：检查类是否已加载，仅对已加载的类创建ClassDefinition</li>
     * <li><b>执行热部署</b>：使用Instrumentation.redefineClasses替换类定义</li>
     * <li><b>返回结果</b>：向客户端返回热部署成功或失败的响应</li>
     * </ol>
     * <p>
     * <b>异常处理：</b>
     * 所有异常都会被捕获并转换为{@link HotDeployResponsePacket}响应，
     * 不会导致服务崩溃，保证系统的稳定性。
     * <p>
     * <b>性能考虑：</b>
     * 记录整个热部署过程的耗时，并在响应中返回给客户端，方便性能监控。
     *
     * @param outputStream 输出流，用于向客户端返回响应
     * @param packet       热部署请求数据包
     * @throws Exception 处理过程中可能抛出的异常，但都会被捕获处理
     */
    @Override
    public void handle(OutputStream outputStream, T packet) throws Exception {
        // 记录开始时间，用于计算热部署耗时
        long start = System.currentTimeMillis();
        
        // 步骤1：获取要热部署的类字节码集合
        // key: 类的全限定名（如 com.example.UserService）
        // value: 类的最新字节码
        Map<String, byte[]> byteCodesMap;
        try {
            byteCodesMap = getByteCodes(packet);
        } catch (Exception e) {
            // 获取字节码失败，返回错误响应
            HotDeployResponsePacket responsePacket = HotDeployResponsePacket.of(
                    false,
                    "Hot deploy error\n" + ExceptionUtil.stacktraceToString(e, -1),
                    DebugToolsBootstrap.serverConfig.getApplicationName()
            );
            writeAndFlushNotException(outputStream, responsePacket);
            return;
        }

        // 步骤2：拼接要重载的类名列表，用于日志输出和响应消息
        String reloadClass = String.join(", ", byteCodesMap.keySet());
        
        // 步骤3：获取目标类加载器
        ClassLoader defaultClassLoader;
        try {
            defaultClassLoader = getClassLoader(packet);
        } catch (DefaultClassLoaderException e) {
            // 类加载器获取失败，返回错误响应
            logger.error("Fail to reload classes {}, msg is {}", reloadClass, e);
            HotDeployResponsePacket responsePacket = HotDeployResponsePacket.of(
                    false,
                    "Hot deploy error, file [" + reloadClass + "]\n" + ExceptionUtil.stacktraceToString(e, -1),
                    DebugToolsBootstrap.serverConfig.getApplicationName()
            );
            writeAndFlushNotException(outputStream, responsePacket);
            return;
        }

        // 步骤4：将新字节码写入到extraClasspath配置的目录中
        // 这样下次JVM重启时，会加载这些新的类文件
        writeFile(defaultClassLoader, byteCodesMap);
        
        // 步骤5：构建需要重定义的类ClassDefinition列表
        // 只有已经被类加载器加载的类才需要重定义
        List<ClassDefinition> definitions = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : byteCodesMap.entrySet()) {
            // 检查类是否已经被加载
            if (ClassLoaderHelper.isClassLoaded(defaultClassLoader, entry.getKey())) {
                // 加载类并创建ClassDefinition对象
                // ClassDefinition包含类对象和新的字节码
                ClassDefinition classDefinition = new ClassDefinition(defaultClassLoader.loadClass(entry.getKey()), entry.getValue());
                definitions.add(classDefinition);
            }
        }

        // 步骤6：检查是否有需要重定义的类
        if (definitions.isEmpty()) {
            // 所有类都还未被加载，不需要重定义，但仍然返回成功, 因为字节码已经写入文件，下次类加载时会使用新的字节码
            logger.warning("There are no classes that need to be redefined. {}", reloadClass);
            HotDeployResponsePacket responsePacket = HotDeployResponsePacket.of(
                    true,
                    "Hot deploy success, file [" + reloadClass + "]",
                    DebugToolsBootstrap.serverConfig.getApplicationName()
            );
            writeAndFlushNotException(outputStream, responsePacket);
            return;
        }

        // 步骤7：执行热部署，替换类定义
        try {
            logger.reload("Reloading classes {}", reloadClass);
            // 使用同步锁保证redefineClasses操作的原子性。防止并发热部署导致的类定义冲突
            synchronized (hotswapLock) {
                Instrumentation instrumentation = DebugToolsBootstrap.INSTANCE.getInstrumentation();
                // 调用JVM的redefineClasses方法，替换类定义，这是Java Instrumentation API的核心功能
                instrumentation.redefineClasses(definitions.toArray(new ClassDefinition[0]));
            }

            // 计算耗时并返回成功响应
            long end = System.currentTimeMillis();
            HotDeployResponsePacket responsePacket = HotDeployResponsePacket.of(
                    true,
                    "Hot deploy success. cost " + (end - start) + " ms. file [" + reloadClass + "]",
                    DebugToolsBootstrap.serverConfig.getApplicationName()
            );
            writeAndFlushNotException(outputStream, responsePacket);
        } catch (Exception e) {
            // 热部署失败，记录错误并返回失败响应
            logger.error("Fail to reload classes {}, msg is {}", reloadClass, e);
            HotDeployResponsePacket responsePacket = HotDeployResponsePacket.of(
                    false,
                    "Hot deploy error, file [" + reloadClass + "]\n" + ExceptionUtil.stacktraceToString(e, -1),
                    DebugToolsBootstrap.serverConfig.getApplicationName()
            );
            writeAndFlushNotException(outputStream, responsePacket);
        }
    }

    /**
     * 将新的类字节码写入到磁盘文件
     * <p>
     * 将热部署的类字节码保存到extraClasspath配置的目录中，这样做有以下好处：
     * <ul>
     * <li><b>持久化修改</b>：JVM重启后仍然使用新的类定义，而不是回退到老版本</li>
     * <li><b>类加载支持</b>：对于还未加载的类，第一次加载时会使用新的字节码</li>
     * <li><b>热部署一致性</b>：保证内存中的类定义和磁盘文件一致</li>
     * </ul>
     * <p>
     * <b>extraClasspath配置说明：</b>
     * <ul>
     * <li>extraClasspath是在debug-tools-agent.properties文件中配置的</li>
     * <li>根据操作系统不同，使用extraClasspath（Linux/Mac）或extraClasspathWin（Windows）</li>
     * <li>该目录需要在应用启动时加入到classpath中</li>
     * <li>通常配置为项目的target或build目录下的一个子目录</li>
     * </ul>
     * <p>
     * <b>文件写入过程：</b>
     * <ol>
     * <li>从插件配置中获取extraClasspath路径</li>
     * <li>将类名转换为文件路径（如com.example.UserService -> com/example/UserService.class）</li>
     * <li>将字节码写入到对应的文件中</li>
     * </ol>
     * <p>
     * <b>注意事项：</b>
     * <ul>
     * <li>如果未配置extraClasspath，会记录错误日志但不会阻断热部署</li>
     * <li>写入文件失败不会影响当前的热部署操作</li>
     * <li>文件路径会自动创建，不需要预先存在</li>
     * </ul>
     *
     * @param defaultClassLoader 目标类加载器
     * @param byteCodesMap       类名到字节码的映射表
     */
    protected void writeFile(ClassLoader defaultClassLoader, Map<String, byte[]> byteCodesMap) {
        // 1. 获取类加载器对应的插件配置
        PluginConfiguration pluginConfiguration = PluginManager.getInstance().getPluginConfiguration(defaultClassLoader);
        if (pluginConfiguration == null) {
            logger.error("Failure to retrieve PluginConfiguration. Please ensure that the project is started in hot reload mode.");
            return;
        }
        
        // 2. 获取extraClasspath配置的路径
        URL[] classpath = pluginConfiguration.getExtraClasspath();
        if (classpath == null || classpath.length == 0) {
            logger.error("{} is null", DebugToolsOSUtils.isWindows() ? "extraClasspathWin" : "extraClasspath");
            return;
        }

        // 3. 获取第一个extraClasspath路径（通常只配置一个）
        String extraClasspath = classpath[0].getPath();
        // 确保路径以文件分隔符结尾
        if (!extraClasspath.endsWith(File.separator)) {
            extraClasspath += File.separator;
        }
        
        // 4. 遍历所有类，将字节码写入到文件
        for (Map.Entry<String, byte[]> entry : byteCodesMap.entrySet()) {
            // 将类名转换为文件路径
            // 如: com.example.UserService -> com/example/UserService.class
            String clasFilePath = entry.getKey().replace(".", File.separator).concat(".class");
            // 将字节码写入到文件中，完整路径如: /var/tmp/debug-tools/classes/com/example/UserService.class
            DebugToolsFileUtils.writeBytes(entry.getValue(), extraClasspath + clasFilePath);
        }
    }
}
