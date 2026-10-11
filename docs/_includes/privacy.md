# Privacy Policy

*MakeACopy for Android. Effective 2026-10-11. The app shows this text
under Options → App → Privacy policy.*

## Who is responsible

MakeACopy is free software developed and published by Christian Kierdorf
as a private, non-commercial project. The source code is at
<https://github.com/egdels/makeacopy>. Questions about this policy go
to <christian.kierdorf@schliweb.de> or to the project's issue tracker at
<https://github.com/egdels/makeacopy/issues>.

## The short version

MakeACopy does not collect, transmit, sell or share any personal data.
There is no account, no analytics, no crash reporting, no advertising, no
tracking and no telemetry. The app does not have the Internet permission,
so it cannot send anything anywhere. Scanning, edge detection, text
recognition (OCR) and PDF creation all run on your device.

## What the app stores on your device

- **Your scans**: the photographed or imported pages, the cropped and
  processed images, the recognised text and the PDF or JPEG files of
  completed scans, so that you can find them again in the library and
  add them to other documents. They are kept in the app's private
  storage until you delete them or the cleanup policy you chose in
  Options → Library removes them.
- **Collections** you create in the library, and which scans belong to
  them.
- **Settings**: the choices in the options dialog, such as the OCR
  language, the export format, the app language and, if you use Inbox
  Mode, the folder you picked for it.
- **Temporary files** while a scan is being processed or shared, in the
  app's cache, which the app cleans up regularly.

Uninstalling the app removes all of this; exported files stay where you
saved them. Your scans, their text and the library are excluded from
Android's backup and from device-to-device transfer, so they never leave
the device that way. Only the settings can be part of a device backup
you have set up with Google, under the terms of that backup service.

## What the app reads

- **The camera image**, only while the camera screen is open. Nothing is
  recorded except the picture you take.
- **Images and PDFs you choose**: through Load on the camera screen, the
  system's share dialog or "Open with". The app reads only the file you
  hand it.
- **The folder you pick for Inbox Mode**, to save your exports there.
  Android grants access to that folder only.
- **MakeACopy's own OCR language packs**, in the Tesseract edition from
  GitHub Releases: the app looks for these add-on apps, which answer to
  its own `de.schliweb.makeacopy.ACTION_TESSDATA` request, and copies
  their language files. It sees no other installed apps.

## What leaves your device

Only what you send yourself: when you save a document, it goes to the
place you pick in the system's file dialog; when you share it, to the
app you pick in the share dialog. **Share logs** in Options → App works
the same way: it opens the share dialog with the app's own log and a
short header (app version, Android version, device model, language,
time), and you decide whether and to whom to send it.

## Android permissions

- `CAMERA`: to photograph documents. Android asks for it when the camera
  screen opens for the first time.
- `VIBRATE`: for the short haptic feedback of Accessibility Mode. Android
  does not ask for this one.
- `USE_BIOMETRIC`: for the optional app lock, which asks for your
  fingerprint, face or screen lock. Android does not ask for this one;
  the app never sees your biometric data, only whether the check
  succeeded.

There is no Internet, location, contacts, microphone or phone permission,
and no permission to read your photos or files in general: files reach
the app only through the system dialogs described above.

## Third parties

The OCR models, the document detection model and the libraries that run
them (PaddleOCR or Tesseract, OpenCV, ONNX Runtime) are part of the app
and work entirely on your device; without the Internet permission none
of them can send anything either. The app contains no third-party SDKs
for advertising, analytics or crash reporting, and no data is shared with
anyone.

## Children

MakeACopy is a document scanner for everyone. It collects no data from
anyone, of any age.

## Changes

This policy changes only if the app's behaviour changes. Each change
updates the effective date above. The current version is always at
<https://egdels.github.io/makeacopy/privacy.html>.
