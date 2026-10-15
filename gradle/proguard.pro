-dontshrink
-dontoptimize
-dontskipnonpubliclibraryclasses
-dontskipnonpubliclibraryclassmembers
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions
-keep class kotlin.Metadata { *; }
-keep class fr.moulou.integrity.IntegrityMod { *; }
-keep class fr.moulou.integrity.client.IntegrityClient { *; }
# Storify et kotlinx.serialization utilisent ces modèles et leurs métadonnées.
-keep class fr.moulou.integrity.model.** { *; }
-keep class fr.moulou.integrity.config.** { *; }
# Les codecs sont un point d'intégration avec Fabric et le contrôle du livrable.
-keep class fr.moulou.integrity.network.** { *; }
-keep class fr.moulou.integrity.api.IntegrityService { public *; }
-keep class fr.moulou.integrity.protocol.SecureExchange { public *; }
-keep class fr.moulou.integrity.protocol.SecureExchange$* { public *; }
-keepclassmembers class * { @kotlinx.serialization.Serializable *; }
# Le pont de version est chargé par ServiceLoader : son interface et ses implémentations gardent leur nom.
-keep interface fr.moulou.integrity.client.ClientBridge { *; }
-keep class fr.moulou.integrity.bridge.** { *; }
# Le pont de version est chargé par ServiceLoader : son interface et ses implémentations gardent leur nom.
-keep interface fr.moulou.integrity.client.ClientBridge { *; }
-keep class fr.moulou.integrity.bridge.** { *; }
# Les types que la façade publique expose gardent leur nom avec elle.
-keep class fr.moulou.integrity.inventory.FileHashCache { public *; }
-keep class fr.moulou.integrity.inventory.FileHashCache$* { public *; }
-keep class fr.moulou.integrity.reference.ReferenceDiff { public *; }
