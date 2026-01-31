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
package io.github.future0923.debug.tools.server.thread;

import io.github.future0923.debug.tools.base.logging.Logger;
import io.github.future0923.debug.tools.common.handler.PacketHandleService;
import io.github.future0923.debug.tools.common.protocal.buffer.ByteBuf;
import io.github.future0923.debug.tools.common.protocal.packet.Packet;
import io.github.future0923.debug.tools.common.protocal.packet.PacketCodec;
import io.github.future0923.debug.tools.common.protocal.packet.response.HeartBeatResponsePacket;
import lombok.Getter;
import lombok.Setter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Map;

/**
 * @author future0923
 */
public class ClientHandleThread extends Thread {

    private static final Logger logger = Logger.getLogger(ClientHandleThread.class);

    @Getter
    private final Socket socket;

    private InputStream inputStream;

    private OutputStream outputStream;

    private final Map<ClientHandleThread, Long> lastUpdateTime2Thread;

    private final PacketHandleService packetHandleService;

    @Setter
    private volatile boolean isClosed = false;

    /**
     * 创建一个用于客户端处理线程
     *
     * @param socket Socket 连接
     * @param lastUpdateTime2Thread 存储每个客户端最后一次访问时间的 Map，当客户端访问时需要更新此客户端的最后一次访问时间
     * @param packetHandleService 用于处理客户端请求的 Service
     */
    public ClientHandleThread(Socket socket, Map<ClientHandleThread, Long> lastUpdateTime2Thread, PacketHandleService packetHandleService) {
        setDaemon(true);
        setName("DebugTools-ClientHandle-Thread-" + socket.getPort());
        this.socket = socket;
        this.lastUpdateTime2Thread = lastUpdateTime2Thread;
        this.packetHandleService = packetHandleService;

        try {
            this.inputStream = socket.getInputStream();
            this.outputStream = socket.getOutputStream();
        } catch (IOException e) {
            logger.error("create SocketHandleThread happen error ", e);
        }
    }

    @Override
    public void run() {
        try {
            while (!isClosed) {
                try {
                    // 获取数据包
                    Packet packet = PacketCodec.INSTANCE.getPacket(inputStream);
                    if (packet != null) {
                        /*
                        更新该客户端最新的访问时间, 当访问时间超过 3 分钟会关闭对应连接，详见 SessionCheckThread
                         */
                        refresh();

                        if (!socket.isClosed()) {
                            packetHandleService.handle(outputStream, packet);
                        } else {
                            this.lastUpdateTime2Thread.remove(this);
                        }
                    } else {
                        boolean isConn = touch(socket.getOutputStream());
                        if (!isConn) {
                            throw new RuntimeException(socket + " close !");
                        }
                    }
                } catch (Exception e) {
                    this.lastUpdateTime2Thread.remove(this);
                    logger.warning("remote client close socket:{} , error:{}", socket, e);
                    return;
                }
            }
        } finally {
            try {
                if (this.outputStream != null) {
                    this.outputStream.close();
                }

                if (this.inputStream != null) {
                    this.inputStream.close();
                }

                if (this.socket != null) {
                    this.socket.close();
                }
            } catch (IOException ignored) {
            }

        }
    }

    private void refresh() {
        this.lastUpdateTime2Thread.put(this, System.currentTimeMillis());
    }

    private boolean touch(OutputStream outputStream) {
        try {
            HeartBeatResponsePacket heartBeatResponsePacket = new HeartBeatResponsePacket();
            ByteBuf byteBuf = PacketCodec.INSTANCE.encode(heartBeatResponsePacket);
            outputStream.write(byteBuf.toByteArray());
            outputStream.flush();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
