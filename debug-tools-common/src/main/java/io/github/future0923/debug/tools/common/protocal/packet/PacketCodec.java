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
package io.github.future0923.debug.tools.common.protocal.packet;

import io.github.future0923.debug.tools.base.hutool.core.io.IoUtil;
import io.github.future0923.debug.tools.base.hutool.core.util.ObjectUtil;
import io.github.future0923.debug.tools.base.logging.Logger;
import io.github.future0923.debug.tools.common.protocal.Command;
import io.github.future0923.debug.tools.common.protocal.buffer.ByteBuf;
import io.github.future0923.debug.tools.common.protocal.packet.request.*;
import io.github.future0923.debug.tools.common.protocal.packet.response.HeartBeatResponsePacket;
import io.github.future0923.debug.tools.common.protocal.packet.response.HotDeployResponsePacket;
import io.github.future0923.debug.tools.common.protocal.packet.response.RunGroovyScriptResponsePacket;
import io.github.future0923.debug.tools.common.protocal.packet.response.RunTargetMethodResponsePacket;
import io.github.future0923.debug.tools.common.protocal.serializer.Serializer;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 协议数据包编解码器
 * <p>
 * 负责将Packet对象编码为字节流，以及将字节流解码为Packet对象
 * <p>
 * 网络传输协议格式（按顺序）：
 * <pre>
 * +--------+--------+------------+---------+------------+--------+------+
 * | 魔数    | 版本   | 序列化算法  | 命令类型 | 结果标志    | 长度   | 数据  |
 * +--------+--------+------------+---------+------------+--------+------+
 * | 4字节  | 1字节  |   1字节    | 1字节   |   1字节    | 4字节  | N字节 |
 * +--------+--------+------------+---------+------------+--------+------+
 * </pre>
 * <p>
 * 字段说明：
 * <ul>
 * <li>魔数(Magic Number): 固定值20240508，用于识别协议数据包，防止非法数据</li>
 * <li>版本(Version): 协议版本号，用于版本兼容性判断</li>
 * <li>序列化算法(Serializer Algorithm): 标识使用的序列化方式（目前使用二进制序列化）</li>
 * <li>命令类型(Command): 标识数据包类型，对应{@link Command}中定义的命令</li>
 * <li>结果标志(Result Flag): 标识操作结果，1表示成功，0表示失败</li>
 * <li>长度(Body Length): 后续数据体的字节长度</li>
 * <li>数据(Body): 具体的业务数据，由各个Packet子类实现序列化</li>
 * </ul>
 *
 * @author future0923
 */
public class PacketCodec {

    private static final Logger logger = Logger.getLogger(PacketCodec.class);

    /**
     * 魔数，用于标识DebugTools协议数据包
     */
    public static final int MAGIC_NUMBER = 20240508;

    /**
     * 魔数字段的字节长度
     */
    public static final int MAGIC_BYTE_LENGTH = 4;

    /**
     * 版本字段的字节长度
     */
    public static final int VERSION_LENGTH = 1;

    /**
     * 命令类型字段的字节长度
     */
    public static final int COMMAND_LENGTH = 1;

    /**
     * 序列化算法字段的字节长度
     */
    public static final int SERIALIZER_ALGORITHM_BYTE_LENGTH = 1;

    /**
     * 数据长度字段的字节长度
     */
    public static final int BODY_LENGTH = 4;

    /**
     * 结果标志字段的字节长度
     */
    public static final int RESULT_FLAG_LENGTH = 1;

    /**
     * 单例实例
     */
    public static final PacketCodec INSTANCE = new PacketCodec();

    /**
     * 命令类型到数据包类的映射表，用于根据命令字节反序列化对应的Packet对象
     */
    private final Map<Byte, Class<? extends Packet>> packetTypeMap = new HashMap<>();

    /**
     * 序列化算法映射表，用于根据算法标识获取对应的序列化器
     */
    private final Map<Byte, Serializer> serializerMap = new HashMap<>();

    /**
     * 私有构造函数，初始化命令映射表和序列化器映射表
     * <p>
     * 注册所有支持的命令类型及对应的Packet实现类：
     * <ul>
     * <li>命令1: {@link Command#HEARTBEAT_REQUEST} - 心跳请求</li>
     * <li>命令2: {@link Command#HEARTBEAT_RESPONSE} - 心跳响应</li>
     * <li>命令3: {@link Command#RUN_TARGET_METHOD_REQUEST} - 运行目标方法请求</li>
     * <li>命令4: {@link Command#RUN_TARGET_METHOD_RESPONSE} - 运行目标方法响应</li>
     * <li>命令5: {@link Command#SERVER_CLOSE_REQUEST} - 服务器关闭请求</li>
     * <li>命令7: {@link Command#CLEAR_RUN_RESULT} - 清空运行结果请求</li>
     * <li>命令8: {@link Command#RUN_GROOVY_SCRIPT_REQUEST} - 运行Groovy脚本请求</li>
     * <li>命令9: {@link Command#RUN_GROOVY_SCRIPT_RESPONSE} - 运行Groovy脚本响应</li>
     * <li>命令10: {@link Command#LOCAL_COMPILER_HOT_DEPLOY_REQUEST} - 本地编译热部署请求</li>
     * <li>命令11: {@link Command#REMOTE_COMPILER_HOT_DEPLOY_REQUEST} - 远程编译热部署请求</li>
     * <li>命令12: {@link Command#REMOTE_COMPILER_HOT_DEPLOY_RESPONSE} - 热部署响应</li>
     * <li>命令13: {@link Command#CHANGE_TRACE_METHOD_REQUEST} - 修改追踪方法请求</li>
     * <li>命令14: {@link Command#RESOURCE_HOT_DEPLOY_REQUEST} - 资源热部署请求</li>
     * </ul>
     */
    private PacketCodec() {
        this.packetTypeMap.put(Command.HEARTBEAT_REQUEST, HeartBeatRequestPacket.class);
        this.packetTypeMap.put(Command.HEARTBEAT_RESPONSE, HeartBeatResponsePacket.class);
        this.packetTypeMap.put(Command.RUN_TARGET_METHOD_REQUEST, RunTargetMethodRequestPacket.class);
        this.packetTypeMap.put(Command.RUN_TARGET_METHOD_RESPONSE, RunTargetMethodResponsePacket.class);
        this.packetTypeMap.put(Command.SERVER_CLOSE_REQUEST, ServerCloseRequestPacket.class);
        this.packetTypeMap.put(Command.CLEAR_RUN_RESULT, ClearRunResultRequestPacket.class);
        this.packetTypeMap.put(Command.RUN_GROOVY_SCRIPT_REQUEST, RunGroovyScriptRequestPacket.class);
        this.packetTypeMap.put(Command.RUN_GROOVY_SCRIPT_RESPONSE, RunGroovyScriptResponsePacket.class);
        this.packetTypeMap.put(Command.LOCAL_COMPILER_HOT_DEPLOY_REQUEST, LocalCompilerHotDeployRequestPacket.class);
        this.packetTypeMap.put(Command.REMOTE_COMPILER_HOT_DEPLOY_REQUEST, RemoteCompilerHotDeployRequestPacket.class);
        this.packetTypeMap.put(Command.REMOTE_COMPILER_HOT_DEPLOY_RESPONSE, HotDeployResponsePacket.class);
        this.packetTypeMap.put(Command.CHANGE_TRACE_METHOD_REQUEST, ChangeTraceMethodRequestPacket.class);
        this.packetTypeMap.put(Command.RESOURCE_HOT_DEPLOY_REQUEST, ResourceHotDeployRequestPacket.class);

        // 注册默认的二进制序列化器
        this.serializerMap.put(Serializer.DEFAULT.getSerializerAlgorithm(), Serializer.DEFAULT);
    }

    /**
     * 从输入流中解码数据包
     * <p>
     * 解码步骤：
     * <ol>
     * <li>读取并校验魔数（4字节），验证是否为有效的DebugTools协议数据</li>
     * <li>读取协议版本（1字节）</li>
     * <li>读取序列化算法标识（1字节）</li>
     * <li>读取命令类型（1字节），根据命令类型确定要实例化的Packet类</li>
     * <li>读取结果标志（1字节），表示操作成功或失败</li>
     * <li>读取数据长度（4字节），用于确定后续要读取的数据体大小</li>
     * <li>读取数据体（N字节），根据前面的长度字段读取完整的业务数据</li>
     * <li>使用序列化器将数据体反序列化到Packet对象中</li>
     * </ol>
     *
     * @param inputStream 输入流，从中读取协议数据
     * @return 解码后的Packet对象，如果解码失败则返回null
     * @throws IOException 读取输入流时发生IO异常
     */
    public Packet getPacket(InputStream inputStream) throws IOException {
        // 1. 读取并校验魔数（4字节）
        int magic = ByteBuf.ByteUtil.toInt(IoUtil.readBytes(inputStream, MAGIC_BYTE_LENGTH));
        if (ObjectUtil.notEqual(MAGIC_NUMBER, magic)) {
            logger.error("magic number not match {}.", magic);
            return null;
        } else {
            // 2. 读取协议版本（1字节）
            byte[] version = IoUtil.readBytes(inputStream, VERSION_LENGTH);
            // 3. 读取序列化算法标识（1字节）
            byte[] serializeAlgorithmByte = IoUtil.readBytes(inputStream, SERIALIZER_ALGORITHM_BYTE_LENGTH);
            // 4. 读取命令类型（1字节）
            byte[] commandByte = IoUtil.readBytes(inputStream, COMMAND_LENGTH);
            // 根据命令类型获取对应的 Packet类
            Class<? extends Packet> requestType = getRequestType(commandByte[0]);
            if (requestType == null) {
                logger.error("requestType {} not found.", commandByte[0]);
                return null;
            }
            // 5. 读取结果标志（1字节）
            byte[] resultFlagByte = IoUtil.readBytes(inputStream, RESULT_FLAG_LENGTH);
            // 6. 读取数据长度（4字节）
            byte[] lengthBytes = IoUtil.readBytes(inputStream, BODY_LENGTH);
            // 7. 根据长度读取数据体（N字节）
            byte[] contentByte = IoUtil.readBytes(inputStream, ByteBuf.ByteUtil.toInt(lengthBytes));
            // 8. 实例化对应的Packet对象
            Packet packet;
            try {
                packet = requestType.newInstance();
            } catch (Exception e) {
                logger.error("deserialize binary class: {} , serialize happen error : {}", requestType, e);
                return null;
            }
            // 9. 设置版本和结果标志
            packet.setVersion(version[0]);
            packet.setResultFlag(resultFlagByte[0]);
            // 10. 使用序列化器反序列化得到实际的数据
            Serializer serializer = this.getSerializer(serializeAlgorithmByte[0]);
            if (serializer != null) {
                serializer.deserialize(packet, contentByte);
                return packet;
            } else {
                return null;
            }
        }
    }

    /**
     * 根据命令类型获取对应的Packet类
     *
     * @param command 命令类型字节
     * @return 对应的Packet类，如果未注册则返回null
     */
    private Class<? extends Packet> getRequestType(byte command) {
        return this.packetTypeMap.get(command);
    }

    /**
     * 根据序列化算法标识获取对应的序列化器
     *
     * @param serializeAlgorithm 序列化算法标识
     * @return 对应的序列化器，如果未注册则返回null
     */
    private Serializer getSerializer(byte serializeAlgorithm) {
        return this.serializerMap.get(serializeAlgorithm);
    }

    /**
     * 将 Packet对象 编码为字节缓冲区，以便于在网络上进行传输
     * <p>
     * 编码步骤：
     * <ol>
     * <li>使用序列化器将Packet对象序列化为字节数组</li>
     * <li>写入魔数（4字节）: 20240508</li>
     * <li>写入协议版本（1字节）: 从packet中获取</li>
     * <li>写入序列化算法标识（1字节）: 默认使用二进制序列化</li>
     * <li>写入命令类型（1字节）: 从packet中获取</li>
     * <li>写入结果标志（1字节）: 从packet中获取（1=成功，0=失败）</li>
     * <li>写入数据长度（4字节）: 序列化后数据体的字节长度</li>
     * <li>写入数据体（N字节）: 序列化后的业务数据</li>
     * </ol>
     *
     * @param packet 要编码的数据包对象
     * @return 包含完整协议数据的字节缓冲区
     */
    public ByteBuf encode(Packet packet) {
        ByteBuf byteBuf = new ByteBuf();
        // 1. 先序列化数据体，获取数据体字节数组
        byte[] bodyBytes = Serializer.DEFAULT.serialize(packet);
        // 2. 按协议格式依次写入各个字段
        byteBuf.writeInt(MAGIC_NUMBER);                                    // 魔数（4字节）
        byteBuf.writeByte(packet.getVersion());                           // 版本（1字节）
        byteBuf.writeByte(Serializer.DEFAULT.getSerializerAlgorithm());   // 序列化算法（1字节）
        byteBuf.writeByte(packet.getCommand());                           // 命令类型（1字节）
        byteBuf.writeByte(packet.getResultFlag());                        // 结果标志（1字节）
        byteBuf.writeInt(bodyBytes.length);                               // 数据长度（4字节）
        byteBuf.writeBytes(bodyBytes);                                    // 数据体（N字节）
        return byteBuf;
    }
}
