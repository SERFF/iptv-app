# --- Attributes that R8 needs to retain for runtime reflection / generics ---
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes RuntimeVisibleTypeAnnotations, AnnotationDefault

# --- kotlinx.serialization ---
# Keep generated serializers and Companion objects of any @Serializable class.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static **$$serializer INSTANCE;
}
-keepclasseswithmembers,includedescriptorclasses class **$$serializer {
    *;
}
-keep,includedescriptorclasses class kotlinx.serialization.** { *; }
-keep,includedescriptorclasses class nl.vanvrouwerff.iptv.data.tmdb.** { *; }
-keep,includedescriptorclasses class nl.vanvrouwerff.iptv.data.xtream.** { *; }
-dontnote kotlinx.serialization.**

# --- Retrofit ---
-keep class retrofit2.** { *; }
-keepattributes Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
# Retrofit interfaces are accessed by reflection.
-keep interface nl.vanvrouwerff.iptv.data.tmdb.** { *; }
-keep interface nl.vanvrouwerff.iptv.data.xtream.** { *; }
-dontwarn retrofit2.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

# --- OkHttp / Okio ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Room ---
# KSP generates implementations alongside the original DAO/Database classes; keep both.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class **_Impl { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-dontwarn androidx.room.paging.**

# --- AndroidX / Lifecycle / DataStore ---
-keep class * extends androidx.lifecycle.ViewModel
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# --- Compose: the compiler plugin already handles most things, but keep tooling-friendly. ---
-dontwarn androidx.compose.**

# --- Media3 / ExoPlayer ---
# Media3 uses reflection to load optional renderers/extensions.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# --- Coil ---
-dontwarn coil.**
-keep class coil.** { *; }

# --- Project: app entry points referenced from manifest / framework ---
-keep class nl.vanvrouwerff.iptv.IptvApp { *; }
-keep class nl.vanvrouwerff.iptv.MainActivity { *; }
-keep class nl.vanvrouwerff.iptv.player.PlayerActivity { *; }

# --- Misc: keep BuildConfig (TMDB token field is read by app code) ---
-keep class nl.vanvrouwerff.iptv.BuildConfig { *; }

# --- Kotlin metadata (needed by serialization & reflection) ---
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# --- Suppress noisy warnings from unused transitive deps ---
-dontwarn java.lang.invoke.StringConcatFactory
