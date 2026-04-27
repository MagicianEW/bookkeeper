# Add project specific ProGuard rules here.
-keep class com.simplebookkeeper.** { *; }

# SQLCipher
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }

# SQLCipher Android
-keep class org.sqlite.** { *; }

# Error-prone annotations (referenced by Tink via transitive deps, not used directly)
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
