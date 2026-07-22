# kotlinx.serialization — keep generated serializers reachable through reflection-free lookup
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class ch.lkmc.blipbird.** {
    *** Companion;
}
-keepclasseswithmembers class ch.lkmc.blipbird.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Retrofit interface methods are looked up reflectively
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# commons-suncalc references compile-only FindBugs annotations
-dontwarn edu.umd.cs.findbugs.annotations.**
