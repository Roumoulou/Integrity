# Renommage seul : ni réduction ni optimisation, pour que le JAR distribué reste le code compilé, renommé, et se reproduise d'un build à l'autre.
-dontshrink
-dontoptimize
-dontskipnonpubliclibraryclasses
-dontskipnonpubliclibraryclassmembers
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions
-keep class kotlin.Metadata { *; }

# Les entrypoints Fabric, que fabric.mod.json nomme.
-keep class fr.moulou.integrity.IntegrityMod { *; }
-keep class fr.moulou.integrity.IntegrityPreLaunch { *; }
-keep class fr.moulou.integrity.client.IntegrityClient { *; }

# Le pont de version est chargé par ServiceLoader : son interface et ses implémentations gardent leur nom.
-keep interface fr.moulou.integrity.client.ClientBridge { *; }
-keep class fr.moulou.integrity.bridge.** { *; }

# Les classes de données gardent leurs noms et leurs membres : kotlinx.serialization porte leurs noms JSON en constantes, mais Storify les lit par
# réflexion Kotlin (memberProperties, createInstance, références de propriétés), et elles ne cachent rien. Tout le reste se renomme : la façade, le
# protocole, les codecs, l'inventaire, les règles, les références, le serveur, les commandes et les internes du client.
-keep class fr.moulou.integrity.model.** { *; }
-keep class fr.moulou.integrity.config.** { *; }

# Les règles de kotlinx.serialization, celles que son JAR embarque (META-INF/proguard/kotlinx-serialization-common.pro).
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** Companion;
}
-if @kotlinx.serialization.internal.NamedCompanion class *
-keepclassmembers class * {
    static <1> *;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-dontnote kotlinx.serialization.**
-dontwarn kotlinx.serialization.internal.ClassValueReferences
-keepclassmembers public class **$$serializer {
    private ** descriptor;
}
