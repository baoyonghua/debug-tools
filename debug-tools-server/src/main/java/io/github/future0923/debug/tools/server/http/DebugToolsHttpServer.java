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
package io.github.future0923.debug.tools.server.http;

import com.sun.net.httpserver.HttpServer;
import io.github.future0923.debug.tools.base.logging.Logger;
import io.github.future0923.debug.tools.server.http.handler.AllClassLoaderHttpHandler;
import io.github.future0923.debug.tools.server.http.handler.ChangePrintSqlTypeHttpHandler;
import io.github.future0923.debug.tools.server.http.handler.GetApplicationNameHttpHandler;
import io.github.future0923.debug.tools.server.http.handler.GetPrintSqlTypeHttpHandler;
import io.github.future0923.debug.tools.server.http.handler.IndexHttpHandler;
import io.github.future0923.debug.tools.server.http.handler.RunResultDetailHttpHandler;
import io.github.future0923.debug.tools.server.http.handler.RunResultTraceHttpHandler;
import io.github.future0923.debug.tools.server.http.handler.RunResultTypeHttpHandler;
import lombok.Getter;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * @author future0923
 */
public class DebugToolsHttpServer {

    private static final Logger logger = Logger.getLogger(DebugToolsHttpServer.class);

    private static volatile DebugToolsHttpServer debugToolsHttpServer;

    private HttpServer httpServer;

    @Getter
    private final int port;

    private volatile boolean started = false;

    public DebugToolsHttpServer(int port) {
        this.port = port;
        try {
            this.httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            logger.error("start http server bind port in {} error", e, port);
            return;
        }

        // IndexHttpHandler -> hello debug tools
        httpServer.createContext(IndexHttpHandler.PATH, IndexHttpHandler.INSTANCE);

        // RunResultTypeHttpHandler -> 用于返回当前运行方法的返回值类型
        httpServer.createContext(RunResultTypeHttpHandler.PATH, RunResultTypeHttpHandler.INSTANCE);

        // RunResultDetailHttpHandler -> 用于返回当前运行方法的返回值
        httpServer.createContext(RunResultDetailHttpHandler.PATH, RunResultDetailHttpHandler.INSTANCE);

        // AllClassLoaderHttpHandler -> 用于返回当前系统中所有的类加载器信息
        httpServer.createContext(AllClassLoaderHttpHandler.PATH, AllClassLoaderHttpHandler.INSTANCE);

        // GetApplicationNameHttpHandler -> 用于返回当前应用程序的名称
        httpServer.createContext(GetApplicationNameHttpHandler.PATH, GetApplicationNameHttpHandler.INSTANCE);

        // RunResultTraceHttpHandler -> 用于返回当前运行方法的调用栈信息
        httpServer.createContext(RunResultTraceHttpHandler.PATH, RunResultTraceHttpHandler.INSTANCE);

        // GetPrintSqlTypeHttpHandler -> 用于返回当前打印SQL的类型
        httpServer.createContext(GetPrintSqlTypeHttpHandler.PATH, GetPrintSqlTypeHttpHandler.INSTANCE);

        // ChangePrintSqlTypeHttpHandler -> 用于修改打印SQL的类型
        httpServer.createContext(ChangePrintSqlTypeHttpHandler.PATH, ChangePrintSqlTypeHttpHandler.INSTANCE);
    }

    public void start() {
        if (!started && httpServer != null) {
            httpServer.start();
            started = true;
            logger.info("start http server trans and bind port in {}", port);
        }
    }

    public void close() {
        if (httpServer != null) {
            httpServer.stop(0);
            started = false;
        }
    }

}
