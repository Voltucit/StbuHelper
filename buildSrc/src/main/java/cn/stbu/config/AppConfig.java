package cn.stbu.config;

/** 应用信息的统一配置入口。修改后重新加载 Gradle，并由你重新打包生效。 */
public final class AppConfig {
    /** 软件名称：桌面名称、App 内名称及 APK 文件名前缀。 */
    public static final String APP_NAME = "川工商小助手";

    /** 对用户显示的软件版本。 */
    public static final String VERSION_NAME = "0.4.3";

    /** Android 内部版本号：发布新版本时递增。 */
    public static final int VERSION_CODE = 2;

    /**
     * 项目图标资源。当前值沿用现有自适应图标。
     * 自定义 PNG/WebP 可放入 app/src/main/res/drawable-nodpi/，
     * 例如 my_logo.png 对应 "@drawable/my_logo"；这里不填写磁盘路径或扩展名。
     */
    public static final String ICON_RESOURCE = "@mipmap/ic_launcher";

    private AppConfig() { }
}
