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
package io.github.future0923.debug.tools.server.http.handler;

import com.sun.net.httpserver.Headers;
import io.github.future0923.debug.tools.server.utils.DebugToolsEnvUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author future0923
 */
public class SpringReadyHttpHandler extends BaseHttpHandler<Void, HttpResponse<Map<String, Object>>> {

    public static final SpringReadyHttpHandler INSTANCE = new SpringReadyHttpHandler();

    public static final String PATH = "/spring/ready";

    private SpringReadyHttpHandler() {

    }

    @Override
    protected HttpResponse<Map<String, Object>> doHandle(Void req, Headers responseHeaders) {
        Map<String, Object> status = loadSpringReadyStatus();
        return HttpResponse.of(Boolean.TRUE.equals(status.get("ready")) ? 200 : 503, status);
    }

    static Map<String, Object> loadSpringReadyStatus() {
        try {
            return DebugToolsEnvUtils.getSpringReadyStatus();
        } catch (Exception e) {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("ready", false);
            status.put("state", "CHECK_ERROR");
            status.put("retryable", false);
            return status;
        }
    }
}
