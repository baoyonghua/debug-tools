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
import io.github.future0923.debug.tools.server.DebugToolsBootstrap;
import io.github.future0923.debug.tools.server.scoket.handler.ServerPacketHandleService;
import lombok.Getter;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * 用于接受客户端连接的线程
 *
 * @author future0923
 */
public class ClientAcceptThread extends Thread {

    private static final Logger logger = Logger.getLogger(ClientAcceptThread.class);

    /**
     * 各个客户端的最后一次访问时间
     */
    @Getter
    private final Map<ClientHandleThread, Long> lastUpdateTime2Thread = new ConcurrentHashMap<>();

    /**
     * 数据包处理服务
     */
    private final PacketHandleService packetHandleService;

    private ServerSocket serverSocket;

    private final CountDownLatch countDownLatch;

    public ClientAcceptThread(CountDownLatch countDownLatch) {
        // 在这里完成对所有数据报处理器的初始化操作
        packetHandleService = new ServerPacketHandleService();

        setName("DebugTools-ClientAccept-Thread");
        setDaemon(true);
        this.countDownLatch = countDownLatch;
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(DebugToolsBootstrap.serverConfig.getTcpPort());
            int bindPort = serverSocket.getLocalPort();
            logger.info("start server trans and bind port in {}", bindPort);
            // 在这里进行下 countDown 以通知 DebugToolsSocketServer 接收线程启动完毕
            countDownLatch.countDown();
            while (!Thread.currentThread().isInterrupted()) {
                Socket socket;
                try {
                    // 接收连接
                    socket = serverSocket.accept();
                } catch (IOException e) {
                    serverSocket.close();
                    return;
                }

                // 当接收到客户端连接后会创建一个 ClientHandleThread 线程来专门处理该客户端的请求
                logger.info("get client conn start handle thread socket: {}", socket);
                ClientHandleThread socketHandleThread = new ClientHandleThread(socket, lastUpdateTime2Thread, packetHandleService);
                socketHandleThread.start();

                /*
                更新该客户端最新的访问时间, 当访问时间超过 3 分钟会关闭对应连接，详见 SessionCheckThread
                 */
                lastUpdateTime2Thread.put(socketHandleThread, System.currentTimeMillis());
            }
        } catch (Exception e) {
            logger.error("运行过程中发生异常，关闭对应链接:{}", e);
        }
    }

    public void close() {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {

            }
        }
        this.interrupt();
        for (ClientHandleThread clientHandleThread : lastUpdateTime2Thread.keySet()) {
            clientHandleThread.interrupt();
        }
    }
}
