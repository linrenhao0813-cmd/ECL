package com.ecl.exception;

/** Stable support code attached to every recoverable launcher failure. */
public enum ErrorCode {
    UNKNOWN("ECL-GEN-0001", "请查看启动器日志获取详细信息"),
    CONFIG_INVALID("ECL-CFG-1001", "检查设置文件和目录权限"),
    AUTH_FAILED("ECL-AUTH-2001", "重新登录账号后重试"),
    AUTH_EXPIRED("ECL-AUTH-2002", "登录状态已失效，请重新授权"),
    DOWNLOAD_FAILED("ECL-DL-3001", "检查网络连接或切换下载源"),
    DOWNLOAD_CHECKSUM("ECL-DL-3002", "删除损坏文件后重新下载"),
    VERSION_INVALID("ECL-VER-4001", "修复或重新安装该游戏版本"),
    JAVA_UNAVAILABLE("ECL-JAVA-5001", "安装或选择符合要求的 Java 运行时"),
    LAUNCH_FAILED("ECL-LAUNCH-6001", "检查启动参数和游戏日志"),
    PROCESS_FAILED("ECL-LAUNCH-6002", "检查安全软件、目录权限和启动参数"),
    PACK_INVALID("ECL-PACK-7001", "确认整合包格式和文件完整性"),
    INTERNAL_FATAL("ECL-FATAL-9001", "导出诊断包并联系技术支持");

    private final String value;
    private final String suggestion;

    ErrorCode(String value, String suggestion) {
        this.value = value;
        this.suggestion = suggestion;
    }

    public String value() {
        return value;
    }

    public String suggestion() {
        return suggestion;
    }
}
