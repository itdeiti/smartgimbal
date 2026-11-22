# Vosk talks to its native library through JNA, which looks up classes, fields and methods by
# name at runtime. Renaming or removing any of them breaks speech recognition.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
