# kotlinx.serialization: keep the generated serializers of the wire types.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class org.simpmusic.wear.protocol.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class org.simpmusic.wear.protocol.**$$serializer { *; }
