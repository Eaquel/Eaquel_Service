-keepattributes Signature,Exceptions,InnerClasses,EnclosingMethod,*Annotation*

-keep class com.eaquel.service.** { *; }
-keepclassmembers class com.eaquel.service.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Database class * { *; }

-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-keepclassmembernames class kotlinx.** { volatile <fields>; }

-keep class androidx.datastore.** { *; }
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
-keep class androidx.lifecycle.** { *; }
-keep class androidx.core.** { *; }
-keep class androidx.work.** { *; }
-keepclassmembers class * extends androidx.work.Worker { *; }
-keepclassmembers class * extends androidx.work.CoroutineWorker { *; }

-dontwarn android.content.IContentProvider
-dontwarn android.app.ActivityThread
-dontwarn kotlin.reflect.**
-dontwarn org.conscrypt.**
-dontwarn javax.annotation.**

-keep class com.eaquel.service.Activity { *; }
-keep class com.eaquel.service.Core { *; }
-keep class com.eaquel.service.Core$BootReceiver { *; }
-keep class com.eaquel.service.Core$PairCodeReceiver { *; }
-keep class com.eaquel.service.AdbPermReceiver { *; }
-keep class com.eaquel.service.AdbNotificationManager { *; }
-keep class com.eaquel.service.Overlay { *; }
