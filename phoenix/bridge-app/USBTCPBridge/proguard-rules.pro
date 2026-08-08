# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in Android SDK tools/proguard/proguard-android.txt

# usb-serial-for-android library
-keep class com.hoho.android.usbserial.** { *; }

# Keep service classes
-keep class com.usbtcpbridge.service.** { *; }
-keep class com.usbtcpbridge.receiver.** { *; }
