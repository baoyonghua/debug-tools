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

import io.github.future0923.debug.tools.common.protocal.Command;
import io.github.future0923.debug.tools.common.protocal.buffer.ByteBuf;
import io.github.future0923.debug.tools.common.protocal.packet.Packet;
import io.github.future0923.debug.tools.common.protocal.packet.response.HotDeployResponsePacket;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地编译热部署请求数据包
 * <p>
 * 该数据包用于实现<b>本地编译、远程热部署</b>的功能。当开发者在IDEA中修改了Java类文件后，
 * 由IDEA在本地完成编译生成字节码文件，然后通过此数据包将编译好的字节码传输到远程运行的应用服务端，
 * 服务端使用 Java Instrumentation API 来动态替换运行中的类定义，实现<b>秒级热部署/热重载</b>，无需重启应用即可看到代码修改效果。
 * <p>
 * <b>工作流程：</b>
 * <ol>
 * <li>开发者在 IDEA 中修改Java源代码</li>
 * <li>IDEA插件检测到文件变化，触发本地编译</li>
 * <li>本地编译器将.java源文件编译为.class字节码文件</li>
 * <li>将字节码文件的路径和内容封装到此数据包中</li>
 * <li>通过 Socket连接 发送到远程应用服务端</li>
 * <li>服务端通过{@link io.github.future0923.debug.tools.server.scoket.handler.LocalCompilerHotDeployRequestHandler}处理</li>
 * <li>使用 Java Instrumentation 的 redefineClasses方法 替换类定义</li>
 * <li>返回{@link HotDeployResponsePacket}告知客户端热部署结果</li>
 * </ol>
 * <p>
 * <b>支持的文件类型：</b>
 * <ul>
 * <li>Java类文件（.class）- 编译后的字节码文件</li>
 * <li>支持多个类文件的批量热部署</li>
 * <li>支持内部类、匿名类等复杂类型</li>
 * </ul>
 * <p>
 * <b>与RemoteCompilerHotDeployRequestPacket的区别：</b>
 * <ul>
 * <li><b>LocalCompiler</b>：在IDEA本地完成编译，传输编译后的字节码（.class），速度快，推荐使用</li>
 * <li><b>RemoteCompiler</b>：传输源代码（.java），在服务端进行编译，适用于本地无法编译的场景</li>
 * </ul>
 * <p>
 * <b>序列化格式说明：</b>
 * 采用自定义二进制序列化格式，结构如下：
 * <pre>
 * +------------------+------------------+------------------+------------------+
 * | Identity长度(4B) | Identity内容(NB) | Header长度(4B)   | Header内容(NB)   |
 * +------------------+------------------+------------------+------------------+
 * | 文件1字节码内容    | 文件2字节码内容    | ...              | 文件N字节码内容    |
 * +------------------+------------------+------------------+------------------+
 * </pre>
 * Header格式：{@code 文件路径1::字节码长度1;;文件路径2::字节码长度2;;...}
 *
 * @author future0923
 * @see io.github.future0923.debug.tools.server.scoket.handler.LocalCompilerHotDeployRequestHandler 服务端处理器
 * @see RemoteCompilerHotDeployRequestPacket 远程编译热部署请求
 * @see HotDeployResponsePacket 热部署响应数据包
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class LocalCompilerHotDeployRequestPacket extends Packet {

    /**
     * 文件路径到字节码的映射表
     * <p>
     * 存储需要热部署的所有类文件信息：
     * <ul>
     * <li><b>Key</b>: 类文件的相对路径，如 {@code "com/example/service/UserService.class"}</li>
     * <li><b>Value</b>: 编译后的字节码数组，即.class文件的二进制内容</li>
     * </ul>
     * <p>
     * <b>支持批量热部署：</b>
     * 可以在一个数据包中同时传输多个类文件，服务端会批量替换这些类的定义，
     * 这样可以保证相互依赖的多个类能够原子性地完成热部署，避免出现中间状态导致的错误。
     * <p>
     * <b>示例：</b>
     * <pre>
     * {
     *   "com/example/service/UserService.class": [字节码数组],
     *   "com/example/service/UserService$1.class": [内部类字节码],
     *   "com/example/dto/UserDTO.class": [字节码数组]
     * }
     * </pre>
     */
    private Map<String, byte[]> filePathByteCodeMap = new HashMap<>();

    /**
     * 类分隔符，用于序列化时分隔不同的类文件信息
     * <p>
     * 在Header字符串中，使用 ";;" 分隔不同类文件的元数据
     * <p>
     * 格式示例：{@code "file1.class::1024;;file2.class::2048;;"}
     */
    private static final String CLASS_SEPARATOR = ";;";

    /**
     * 类信息分隔符，用于分隔文件路径和字节码长度
     * <p>
     * 在每个类文件的元数据中，使用 "::" 分隔文件路径和字节码长度
     * <p>
     * 格式示例：{@code "com/example/UserService.class::1024"}
     */
    private static final String CLASS_INFO_SEPARATOR = "::";

    /**
     * 类加载器的唯一标识符
     * <p>
     * 用于在服务端定位正确的类加载器，确保类被加载到正确的类加载器中。
     * 在多类加载器环境下（如Spring Boot的多模块应用），不同的类可能需要被加载到不同的类加载器中。
     * <p>
     * <b>格式：</b>{@code "类加载器名称@哈希码"}
     * <p>
     * <b>示例：</b>
     * <ul>
     * <li>{@code "org.springframework.boot.loader.LaunchedURLClassLoader@5a07e868"}</li>
     * <li>{@code "sun.misc.Launcher$AppClassLoader@18b4aac2"}</li>
     * </ul>
     */
    private String identity;

    /**
     * 获取命令类型
     * <p>
     * 返回该数据包对应的命令类型：{@link Command#LOCAL_COMPILER_HOT_DEPLOY_REQUEST}（值为10）
     * <p>
     * 该命令用于标识这是一个本地编译热部署请求，服务端根据此命令类型
     * 路由到对应的处理器{@link io.github.future0923.debug.tools.server.scoket.handler.LocalCompilerHotDeployRequestHandler}
     *
     * @return 命令类型字节值：10
     */
    @Override
    public Byte getCommand() {
        return Command.LOCAL_COMPILER_HOT_DEPLOY_REQUEST;
    }

    /**
     * 二进制序列化
     * <p>
     * 将当前对象序列化为字节数组，用于网络传输。采用自定义的紧凑型二进制格式，
     * 相比JSON格式可以显著减少字节码文件的传输体积。
     * <p>
     * <b>序列化格式：</b>
     * <pre>
     * +----------------------------+
     * | Identity长度 (4字节)        |
     * +----------------------------+
     * | Identity内容 (N字节)        |
     * +----------------------------+
     * | Header长度 (4字节)          |
     * +----------------------------+
     * | Header内容 (N字节)          |
     * | 格式：文件路径1::长度1;;... |
     * +----------------------------+
     * | 文件1字节码 (长度1字节)     |
     * +----------------------------+
     * | 文件2字节码 (长度2字节)     |
     * +----------------------------+
     * | ...                        |
     * +----------------------------+
     * </pre>
     * <p>
     * <b>序列化步骤：</b>
     * <ol>
     * <li>序列化类加载器标识：先写入长度(4字节)，再写入UTF-8编码的内容</li>
     * <li>构建Header字符串：包含所有文件的路径和字节码长度信息</li>
     * <li>序列化Header：先写入长度(4字节)，再写入UTF-8编码的内容</li>
     * <li>按顺序写入所有文件的字节码内容（不需要再写长度，Header中已包含）</li>
     * </ol>
     * <p>
     * <b>示例数据：</b>
     * <pre>
     * Identity长度: 4字节 -> 0x00000032 (50字节)
     * Identity内容: 50字节 -> "org.springframework.boot.loader.LaunchedURLClassLoader@5a07e868"
     * Header长度: 4字节 -> 0x00000040 (64字节)
     * Header内容: 64字节 -> "com/example/UserService.class::1024;;com/example/UserDTO.class::512;;"
     * 文件1字节码: 1024字节 -> [字节码内容]
     * 文件2字节码: 512字节 -> [字节码内容]
     * </pre>
     *
     * @return 序列化后的字节数组，包含完整的类加载器信息和所有文件的字节码
     */
    @Override
    public byte[] binarySerialize() {
        ByteBuf byteBuf = new ByteBuf();
        // 1. 序列化 类加载器 标识：长度 + 内容
        byte[] identityInfo = identity.getBytes(StandardCharsets.UTF_8);
        byteBuf.writeInt(identityInfo.length);  // 写入Identity长度
        byteBuf.writeBytes(identityInfo);        // 写入Identity内容

        // 2. 构建Header信息和收集文件内容
        StringBuilder fileHeaderInfo = new StringBuilder();
        List<byte[]> fileContent = new ArrayList<>();
        filePathByteCodeMap.forEach((filePath, byteCode) -> {
            // Header格式：文件路径::字节码长度;;
            fileHeaderInfo.append(filePath)
                    .append(CLASS_INFO_SEPARATOR)  // 添加 "::"
                    .append(byteCode.length)
                    .append(CLASS_SEPARATOR);      // 添加 ";;"
            fileContent.add(byteCode);
        });

        // 3. 序列化Header：长度 + 内容
        byte[] headerInfo = fileHeaderInfo.toString().getBytes(StandardCharsets.UTF_8);
        byteBuf.writeInt(headerInfo.length);     // 写入Header长度
        byteBuf.writeBytes(headerInfo);          // 写入Header内容

        // 4. 按顺序写入所有文件的字节码内容
        fileContent.forEach(byteBuf::writeBytes);

        return byteBuf.toByteArray();
    }

    /**
     * 二进制反序列化
     * <p>
     * 从字节数组中反序列化出对象数据，填充到当前对象的字段中。
     * 反序列化的顺序和格式必须与{@link #binarySerialize()}方法严格对应。
     * <p>
     * <b>反序列化步骤：</b>
     * <ol>
     * <li>读取类加载器标识：先读取长度(4字节)，再根据长度读取内容并转换为字符串</li>
     * <li>读取Header信息：先读取长度(4字节)，再根据长度读取内容并转换为字符串</li>
     * <li>解析Header字符串：按 ";;" 分隔得到每个文件的元数据</li>
     * <li>解析每个文件元数据：按 "::" 分隔得到文件路径和字节码长度</li>
     * <li>根据长度读取每个文件的字节码内容，存入filePathByteCodeMap</li>
     * </ol>
     * <p>
     * <b>Header解析示例：</b>
     * <pre>
     * Header字符串: "com/example/UserService.class::1024;;com/example/UserDTO.class::512;;"
     *
     * 按";;"分割:
     *   [0] = "com/example/UserService.class::1024"
     *   [1] = "com/example/UserDTO.class::512"
     *   [2] = "" (空字符串，忽略)
     *
     * 对每个元素按"::"分割:
     *   [0] -> 文件路径="com/example/UserService.class", 长度=1024
     *   [1] -> 文件路径="com/example/UserDTO.class", 长度=512
     * </pre>
     * <p>
     * <b>容错处理：</b>
     * <ul>
     * <li>如果某个元数据项的":"分隔后不是恰好2个部分，则跳过该项</li>
     * <li>确保按Header中记录的长度精确读取每个文件的字节码</li>
     * </ul>
     *
     * @param bytes 待反序列化的字节数组，包含完整的序列化数据
     */
    @Override
    public void binaryDeserialization(byte[] bytes) {
        ByteBuf byteBuf = ByteBuf.wrap(bytes);

        // 1. 读取类加载器标识
        int identityLength = byteBuf.readInt();          // 读取Identity长度
        byte[] identityByte = new byte[identityLength];
        byteBuf.readBytes(identityByte);                 // 读取Identity内容
        identity = new String(identityByte, StandardCharsets.UTF_8);

        // 2. 读取Header信息 -> 在Header中包含着所需要进行热部署的文件元数据
        int headerLength = byteBuf.readInt();            // 读取Header长度
        byte[] headerByte = new byte[headerLength];
        byteBuf.readBytes(headerByte);                   // 读取Header内容
        String headerInfo = new String(headerByte, StandardCharsets.UTF_8);

        // 3. 解析Header字符串，提取每个文件的路径和字节码长度信息
        String[] split = headerInfo.split(CLASS_SEPARATOR);  // 按";;"分隔，每个需要进行热部署的文件使用 ;; 来进行分割
        for (String item : split) {
            String[] split1 = item.split(CLASS_INFO_SEPARATOR);  // 类文件的路径和字节码长度之间使用 :: 分隔
            if (split1.length != 2) {
                // 格式不正确（如空字符串），跳过
                continue;
            }
            String filePath = split1[0];                  // 文件路径
            int fileLength = Integer.parseInt(split1[1]); // 字节码长度

            // 4. 根据长度读取字节码内容
            byte[] fileByteCode = new byte[fileLength];
            byteBuf.readBytes(fileByteCode);

            // 5. 存入映射表
            filePathByteCodeMap.put(filePath, fileByteCode);
        }
    }

    /**
     * 添加一个类文件到热部署列表
     * <p>
     * 用于客户端（IDEA插件）构造热部署请求时，逐个添加需要热部署的类文件。
     * 支持添加多个文件，实现批量热部署。
     * <p>
     * <b>使用示例：</b>
     * <pre>
     * LocalCompilerHotDeployRequestPacket packet = new LocalCompilerHotDeployRequestPacket();
     * packet.setIdentity("org.springframework.boot.loader.LaunchedURLClassLoader@5a07e868");
     * packet.add("com/example/service/UserService.class", userServiceByteCode);
     * packet.add("com/example/service/OrderService.class", orderServiceByteCode);
     * // 发送到服务端
     * socketClient.send(packet);
     * </pre>
     *
     * @param fileName     类文件的相对路径，如 "com/example/service/UserService.class"
     * @param fileByteCode 编译后的字节码数组，即.class文件的二进制内容
     */
    public void add(String fileName, byte[] fileByteCode) {
        filePathByteCodeMap.put(fileName, fileByteCode);
    }
}
