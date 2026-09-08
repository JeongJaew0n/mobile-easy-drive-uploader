# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.jjw.easygallery.**$$serializer { *; }
-keepclassmembers class com.jjw.easygallery.** { *** Companion; }
-keepclasseswithmembers class com.jjw.easygallery.** { kotlinx.serialization.KSerializer serializer(...); }

# Retrofit
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# smbj(SMB2/3): 이벤트 버스(mbassador)가 리플렉션으로 핸들러를 찾고, BouncyCastle 은 선택 의존을 참조한다 (docs/NAS_STORAGE.md §5)
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
# Kerberos(SPNEGO)·EL 은 smbj/mbassador 의 선택 경로 — NTLM 만 쓰므로 클래스가 없어도 된다
-dontwarn org.ietf.jgss.**
-dontwarn javax.el.**
