#!/usr/bin/env python3
"""Static checks complement core tests; these do not replace device/provider tests."""
from pathlib import Path
import hashlib
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
ns = '{http://schemas.android.com/apk/res/android}'
manifest = ET.parse(root / 'app/src/main/AndroidManifest.xml').getroot()
permissions = [(p.attrib[ns + 'name'], p.attrib.get(ns + 'maxSdkVersion'))
               for p in manifest.findall('uses-permission')]
assert permissions == [('android.permission.WRITE_EXTERNAL_STORAGE', '28')], permissions
source = (root / 'app/src/main/java/org/thermalfusion/app/storage/ImageSaver.java').read_text()
assert 'MediaStore.Images.Media.IS_PENDING, 1' in source
assert 'MediaStore.Images.Media.IS_PENDING, 0' in source
assert 'MediaStore.Images.Media.RELATIVE_PATH' in source
assert 'MediaStore.VOLUME_EXTERNAL_PRIMARY' in source
assert 'MediaScannerConnection.scanFile' in source
assert 'Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)' in source
assert 'MANAGE_EXTERNAL_STORAGE' not in source and 'READ_MEDIA_' not in source
usb = (root / 'app/src/main/java/org/thermalfusion/app/sdk/UsbPermissionCoordinator.java').read_text()
assert 'PendingIntent.FLAG_IMMUTABLE' in usb and 'FLAG_MUTABLE' not in usb
assert '.setPackage(context.getPackageName())' in usb
assert 'Context.RECEIVER_NOT_EXPORTED' in usb and 'manager.hasPermission' in usb
assert 'context.unregisterReceiver(receiver)' in usb
wrapper = root / 'gradle/wrapper/gradle-wrapper.jar'
assert hashlib.sha256(wrapper.read_bytes()).hexdigest() == '2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046'
properties = (root / 'gradle/wrapper/gradle-wrapper.properties').read_text()
assert 'distributionSha256Sum=f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6' in properties
print('PASS: manifest storage permission policy, Android destination source checks, official Gradle wrapper checksum')
