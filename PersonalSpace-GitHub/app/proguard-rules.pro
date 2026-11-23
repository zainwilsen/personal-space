# SQLCipher ProGuard rules
-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.database.sqlcipher.** { *; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.personalspace.vault.**$$serializer { *; }
-keepclassmembers class app.personalspace.vault.** { *** Companion; }
-keepclasseswithmembers class app.personalspace.vault.** { kotlinx.serialization.KSerializer serializer(...); }

# BouncyCastle
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.crypto.** { *; }

# Room entities + domain models (reflective construction)
-keep class app.personalspace.vault.data.local.entity.** { *; }
-keep class app.personalspace.vault.domain.model.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { <init>(); }
-keepclassmembers @androidx.room.Entity class * { *; }

# Compose runtime keeps its own rules; keep enums used by serialization
-keepclassmembers enum app.personalspace.vault.** { *; }

# WorkManager workers instantiated by name
-keep class app.personalspace.vault.data.sync.**Worker { *; }
