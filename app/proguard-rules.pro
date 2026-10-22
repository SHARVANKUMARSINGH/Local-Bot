# Local Bots Proguard Rules
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}
-keep class com.localbots.app.domain.nativebridge.** { *; }
-keep class com.localbots.app.legacy.** { *; }
-keepclassmembers class * {
    native <methods>;
}
