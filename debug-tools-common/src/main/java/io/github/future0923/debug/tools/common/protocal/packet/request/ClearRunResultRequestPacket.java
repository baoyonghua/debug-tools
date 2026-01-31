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
package io.github.future0923.debug.tools.common.protocal.packet.request;

import io.github.future0923.debug.tools.base.logging.Logger;
import io.github.future0923.debug.tools.common.protocal.Command;
import io.github.future0923.debug.tools.common.protocal.packet.Packet;
import io.github.future0923.debug.tools.common.utils.DebugToolsJsonUtils;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.nio.charset.StandardCharsets;

/**
 * 清除运行结果请求数据包
 * <p>
 * 该数据包用于客户端（IDEA插件）向服务端（被调试应用）发送清除运行结果缓存的请求。
 * 当用户关闭运行结果面板或切换到其他调试任务时，需要清除服务端缓存的方法执行结果和方法调用追踪信息，
 * 以释放内存资源，防止内存泄漏。
 * <p>
 * 清除的缓存类型包括：
 * <ul>
 * <li>方法执行结果对象缓存（通过fieldOffset定位）：包括方法返回值及其内部字段的详细数据</li>
 * <li>方法调用追踪链路缓存（通过traceOffset定位）：包括方法调用的堆栈追踪树结构</li>
 * </ul>
 * <p>
 * 使用场景：
 * <ul>
 * <li>用户关闭IDEA中的运行结果面板时</li>
 * <li>用户切换到新的调试任务时</li>
 * <li>用户主动清理调试缓存时</li>
 * </ul>
 *
 * @author future0923
 * @see io.github.future0923.debug.tools.server.scoket.handler.ClearRunResultRequestHandler 服务端处理器
 * @see io.github.future0923.debug.tools.server.utils.DebugToolsResultUtils#removeCache(String) 缓存清除方法
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ClearRunResultRequestPacket extends Packet {

    private static final Logger logger = Logger.getLogger(ClearRunResultRequestPacket.class);

    /**
     * 方法执行结果的偏移路径（Offset Path）
     * <p>
     * 这是一个用于唯一标识和定位方法执行结果对象及其内部字段的路径字符串。
     * <p>
     * <b>路径格式：</b>
     * <pre>
     * 根路径: "唯一标识符"
     * 对象字段: "唯一标识符/字段偏移量@类型"
     * 嵌套字段: "唯一标识符/字段1偏移量@类型1/字段2偏移量@类型2/..."
     * 数组元素: "唯一标识符/索引@COLLECTION"
     * Map元素:  "唯一标识符/keyHashCode@MAP"
     * </pre>
     * <p>
     * <b>示例：</b>
     * <ul>
     * <li>根对象: {@code "method_result_123456"}</li>
     * <li>对象字段: {@code "method_result_123456/24@OBJECT"} - 表示根对象偏移量为24的字段</li>
     * <li>数组元素: {@code "method_result_123456/0@COLLECTION"} - 表示数组的第0个元素</li>
     * <li>Map元素: {@code "method_result_123456/789456@MAP"} - 表示Map中key的hashCode为789456的元素</li>
     * <li>嵌套字段: {@code "method_result_123456/24@OBJECT/48@STRING"} - 表示根对象的某个字段的某个子字段</li>
     * </ul>
     * <p>
     * <b>作用：</b>
     * <ul>
     * <li>服务端使用该路径从CACHE中定位并清除对应的运行结果对象</li>
     * <li>支持清除方法返回值以及展开查看的内部字段数据</li>
     * <li>使用JDK Unsafe的字段偏移量机制实现高效的字段访问</li>
     * </ul>
     * <p>
     * <b>为空时：</b>表示不需要清除方法执行结果缓存
     *
     * @see io.github.future0923.debug.tools.server.utils.DebugToolsResultUtils#getValueByOffset(String) 通过offset获取值
     * @see io.github.future0923.debug.tools.server.utils.DebugToolsResultUtils#CACHE 服务端缓存Map
     */
    private String fieldOffset;

    /**
     * 方法调用追踪的偏移路径（Trace Offset Path）
     * <p>
     * 这是一个用于唯一标识方法调用追踪链路（调用堆栈树）的路径字符串。
     * <p>
     * <b>路径格式：</b>
     * <pre>
     * "trace_唯一标识符"
     * </pre>
     * <p>
     * <b>示例：</b>
     * <ul>
     * <li>{@code "trace_method_run_123456"} - 标识一次方法执行的完整调用追踪链路</li>
     * </ul>
     * <p>
     * <b>追踪内容：</b>
     * 记录了方法执行过程中的完整调用链路，包括：
     * <ul>
     * <li>每个被调用方法的名称、类名</li>
     * <li>方法调用的层级关系（父子调用关系）</li>
     * <li>每个方法的执行耗时</li>
     * <li>方法的入参和返回值</li>
     * </ul>
     * <p>
     * <b>数据结构：</b>
     * 追踪数据以树形结构存储（{@link io.github.future0923.debug.tools.base.trace.MethodTreeNode}），
     * 可以清晰展示方法调用的完整路径和层级关系。
     * <p>
     * <b>作用：</b>
     * <ul>
     * <li>服务端使用该路径从CACHE中定位并清除对应的方法调用追踪树数据</li>
     * <li>释放方法调用链路追踪占用的内存资源</li>
     * <li>支持在IDEA插件中展示方法调用的火焰图或调用树</li>
     * </ul>
     * <p>
     * <b>为空时：</b>表示不需要清除方法追踪缓存，或该方法执行时未开启追踪功能
     *
     * @see io.github.future0923.debug.tools.base.trace.MethodTreeNode 方法调用树节点
     * @see io.github.future0923.debug.tools.server.utils.DebugToolsResultUtils#removeCache(String) 缓存清除方法
     */
    private String traceOffset;

    /**
     * 无参构造函数
     * <p>
     * 用于反序列化时创建空对象，然后通过setter方法填充数据
     */
    public ClearRunResultRequestPacket() {
    }

    /**
     * 带参构造函数
     * <p>
     * 用于客户端（IDEA插件）构造清除请求数据包
     *
     * @param fieldOffset  方法执行结果的偏移路径，为null或空字符串时不清除结果缓存
     * @param traceOffset  方法调用追踪的偏移路径，为null或空字符串时不清除追踪缓存
     */
    public ClearRunResultRequestPacket(String fieldOffset, String traceOffset) {
        this.fieldOffset = fieldOffset;
        this.traceOffset = traceOffset;
    }

    /**
     * 获取命令类型
     * <p>
     * 返回该数据包对应的命令类型：{@link Command#CLEAR_RUN_RESULT}（值为7）
     * <p>
     * 该命令用于标识这是一个清除运行结果的请求，服务端根据此命令类型
     * 路由到对应的处理器{@link io.github.future0923.debug.tools.server.scoket.handler.ClearRunResultRequestHandler}
     *
     * @return 命令类型字节值：7
     */
    @Override
    public Byte getCommand() {
        return Command.CLEAR_RUN_RESULT;
    }

    /**
     * 二进制序列化
     * <p>
     * 将当前对象序列化为字节数组，用于网络传输。
     * 采用JSON格式序列化，然后转换为UTF-8编码的字节数组。
     * <p>
     * <b>序列化格式示例：</b>
     * <pre>
     * {
     *   "fieldOffset": "method_result_123456/24@OBJECT",
     *   "traceOffset": "trace_method_run_123456"
     * }
     * </pre>
     *
     * @return 序列化后的字节数组，UTF-8编码的JSON数据
     */
    @Override
    public byte[] binarySerialize() {
        return DebugToolsJsonUtils.toJsonStr(this).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 二进制反序列化
     * <p>
     * 从字节数组中反序列化出对象数据，填充到当前对象的字段中。
     * 先将字节数组转换为UTF-8字符串，然后解析JSON格式数据。
     * <p>
     * <b>处理逻辑：</b>
     * <ol>
     * <li>检查字节数组是否为空，为空则直接返回</li>
     * <li>将字节数组转换为UTF-8编码的字符串</li>
     * <li>校验字符串是否为有效的JSON格式</li>
     * <li>解析JSON字符串为ClearRunResultRequestPacket对象</li>
     * <li>将解析出的数据填充到当前对象的字段中</li>
     * </ol>
     * <p>
     * <b>异常处理：</b>
     * 如果接收到的数据不是有效的JSON格式，会记录警告日志并跳过反序列化，
     * 此时对象的字段保持为null。
     *
     * @param bytes 待反序列化的字节数组，UTF-8编码的JSON数据
     */
    @Override
    public void binaryDeserialization(byte[] bytes) {
        // 1. 空数据检查
        if (bytes == null || bytes.length == 0) {
            return;
        }
        // 2. 字节数组转字符串
        String jsonString = new String(bytes, StandardCharsets.UTF_8);
        // 3. JSON格式校验
        if (!DebugToolsJsonUtils.isTypeJSON(jsonString)) {
            logger.warning("The data ClearRunResultRequestPacket received is not JSON, {}", jsonString);
            return;
        }
        // 4. 解析JSON并填充字段
        ClearRunResultRequestPacket packet = DebugToolsJsonUtils.toBean(jsonString, ClearRunResultRequestPacket.class);
        this.setFieldOffset(packet.getFieldOffset());
        this.setTraceOffset(packet.getTraceOffset());
    }
}
