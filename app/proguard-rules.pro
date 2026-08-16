# Components are also referenced from the manifest, but keep their names explicit for
# device-side diagnostics and OEM telephony callbacks.
-keep class io.github.venompool888.capsulesms.sms.*Receiver { *; }
-keep class com.klinker.android.send_message.MmsFileProvider { *; }

# The dependency contains an unused legacy network fallback. R8 removes it because Capsule SMS
# calls only Android's SmsManager transport; suppress resolution warnings for those removed paths.
-dontwarn com.squareup.okhttp.**
