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

import io.github.future0923.debug.tools.common.protocal.buffer.ByteBuf;
import lombok.Getter;
import lombok.Setter;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 需要加入到{@link PacketCodec}中
 *
 * @author future0923
 */
public abstract class Packet {

    private static final String EMPTY_BYTE = "\u0000";
    private static final String EMPTY_STRING = "";
    @Setter
    @Getter
    private byte version;
    @Setter
    @Getter
    private byte resultFlag = SUCCESS;
    public static final byte SUCCESS = 1;
    public static final byte FAIL = 0;

    public Packet() {
    }

    /**
     * 获取对应的命令类型
     *
     * @return
     * @see io.github.future0923.debug.tools.common.protocal.Command
     */
    public abstract Byte getCommand();

    /**
     * 二进制序列化
     * <p>
     * 将当前对象序列化为字节数组，用于网络传输。
     *
     * @return
     */
    public abstract byte[] binarySerialize();

    /**
     * 二进制反序列化
     * <p>
     * 从字节数组中反序列化出对象数据，填充到当前Packet对象的字段中
     *
     * @param bytes 实际在网络中进行传输的二进制数据
     */
    public abstract void binaryDeserialization(byte[] bytes);

    public boolean isSuccess() {
        return resultFlag == SUCCESS;
    }

    /**
     * 将当前对象序列化为字节数组，并写入到输出流中, 以便于将数据传输到服务端/客户端
     *
     * @param outputStream
     * @throws IOException
     */
    public void writeAndFlush(OutputStream outputStream) throws IOException {
        ByteBuf byteBuf = PacketCodec.INSTANCE.encode(this);
        outputStream.write(byteBuf.toByteArray());
        outputStream.flush();
    }
}
