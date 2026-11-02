# Keep PDFBox Android library classes and dependencies from being stripped by R8
-keep class com.tom_roush.pdfbox.** { *; }
-keepclassmembers class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**

-keep class org.apache.pdfbox.** { *; }
-dontwarn org.apache.pdfbox.**

-keep class org.apache.fontbox.** { *; }
-dontwarn org.apache.fontbox.**

-keep class com.tom_roush.harmony.** { *; }
-dontwarn com.tom_roush.harmony.**
