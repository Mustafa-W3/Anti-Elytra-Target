-dontshrink
-dontoptimize
-dontnote
-dontwarn

-keepattributes Exceptions,InnerClasses,Signature,Deprecated,SourceFile,LineNumberTable,*Annotation*,EnclosingMethod

-keep public class com.antielytratarget.AntiElytraTargetPlugin {
    public <init>();
    public void onLoad();
    public void onEnable();
    public void onDisable();
}

-keepclassmembers,includedescriptorclasses class * implements org.bukkit.event.Listener {
    @org.bukkit.event.EventHandler <methods>;
}

-keep class net.kyori.** { *; }
-keep class com.github.retrooper.packetevents.** { *; }
-keep class io.github.retrooper.packetevents.** { *; }
-keep class com.antielytratarget.shaded.** { *; }
-printmapping build/obfuscation-mapping.txt
