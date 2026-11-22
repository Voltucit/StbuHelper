-keepclassmembers class cn.stbu.helper.ui.LocalBridge { @android.webkit.JavascriptInterface <methods>; }

# Gson constructs these classes reflectively and reads their server or on-device field names.
-keep class com.hualala.linyu.model.BleDownRateData {
    public <init>();
    <fields>;
}
-keep class com.hualala.linyu.model.BleUploadData {
    public <init>();
    <fields>;
}
-keep class com.hualala.linyu.data.BleShowerController$Session {
    <init>(...);
    <fields>;
}
