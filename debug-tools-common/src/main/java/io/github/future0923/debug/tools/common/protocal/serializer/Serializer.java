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
package io.github.future0923.debug.tools.common.protocal.serializer;

import io.github.future0923.debug.tools.common.protocal.packet.Packet;

/**
 * @author future0923
 */
public interface Serializer {

    BinarySerializer DEFAULT = new BinarySerializer();

    /**
     * 获取序列化算法
     *
     * @return
     */
    byte getSerializerAlgorithm();

    /**
     * 序列化, 用于将一个 Packet 对象序列化为字节数组, 以便于在网络上进行传输
     *
     * @param packet
     * @return
     */
    byte[] serialize(Packet packet);

    /**
     * 反序列化, 用于将字节数组反序列化, 并将反序列化的结果塞入到Packet中
     *
     * @param packet
     * @param bytes
     */
    void deserialize(Packet packet, byte[] bytes);
}
