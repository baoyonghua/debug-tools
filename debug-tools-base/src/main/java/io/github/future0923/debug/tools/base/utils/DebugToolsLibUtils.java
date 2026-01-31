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
package io.github.future0923.debug.tools.base.utils;

import io.github.future0923.debug.tools.base.logging.AnsiLog;

import java.io.File;

/**
 * @author future0923
 */
@SuppressWarnings("ResultOfMethodCallIgnored")
public class DebugToolsLibUtils {

    /**
     * DebugTools目录, 如果未在系统环境变量中设置 home dir，
     * 则默认在 user.home 指定的目录下创建 .debugTools 目录来作为 DebugTools 的主目录
     */
    private static File DEBUG_TOOLS_HOME_DIR;

    /**
     * DebugTools Lib目录, 如果未在系统环境变量中设置 home dir，
     * 则默认在 user.home 指定的目录 .debugTools/lib 目录来作为 DebugTools 的 lib 目录
     */
    private static final File DEBUG_TOOLS_LIB_DIR;

    static {
        String debugToolsLibDirEnv = System.getenv("DEBUG_TOOLS_HOME_DIR");
        if (debugToolsLibDirEnv != null) {
            DEBUG_TOOLS_HOME_DIR = new File(debugToolsLibDirEnv);
            AnsiLog.info("DEBUG_TOOLS_LIB_DIR: " + debugToolsLibDirEnv);
        } else {
            DEBUG_TOOLS_HOME_DIR = new File(System.getProperty("user.home") + File.separator + ".debugTools");
        }

        try {
            DEBUG_TOOLS_HOME_DIR.mkdirs();
        } catch (Throwable t) {
            //ignore
        }

        if (!DEBUG_TOOLS_HOME_DIR.exists()) {
            // 如果在用户家目录下创建失败，则尝试在临时目录下创建 .debugTools 目录
            DEBUG_TOOLS_HOME_DIR = new File(System.getProperty("java.io.tmpdir") + File.separator + ".debugTools");
            try {
                DEBUG_TOOLS_HOME_DIR.mkdirs();
            } catch (Throwable e) {
                // ignore
            }
        }

        if (!DEBUG_TOOLS_HOME_DIR.exists()) {
            // -D: 在程序运行时动态设置系统属性（System Properties）。这些属性会被加载到 JVM 的系统属性集合中，程序可以在运行时读取这些属性值
            System.err.println("Can not find directory to save debug tools lib. please try to set user home by -Duser.home=");
        }


        // 创建DebugTools Lib目录, 用于存放
        DEBUG_TOOLS_LIB_DIR = new File(DEBUG_TOOLS_HOME_DIR, "lib");
        if (!DEBUG_TOOLS_LIB_DIR.exists()) {
            try {
                DEBUG_TOOLS_LIB_DIR.mkdirs();
            } catch (Throwable e) {
                // ignore
            }
        }

    }

    public static File getDebugToolsHomeDir() {
        return DEBUG_TOOLS_HOME_DIR;
    }

    public static File getDebugToolsLibDir() {
        return DEBUG_TOOLS_LIB_DIR;
    }
}
