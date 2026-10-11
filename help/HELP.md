# Help

MakeACopy turns paper into searchable PDFs, entirely on your phone. A scan
goes through four screens: Camera, Crop, OCR and Export. Everything you
can set is under **Options**, the slider button on the camera screen.

## Taking a picture

- Hold the phone above the page so that all four corners are visible.
  Tap the preview to focus.
- Tap **Scan** to take the picture. The flashlight button helps in poor
  light, the zoom button gets closer to small pages.
- **Load** takes an image or a PDF that is already on your phone instead.
  You can also share images and PDFs to MakeACopy from other apps, or
  open them with it.

## Cropping

- MakeACopy finds the edges of the page itself. Drag the corners or
  edges where they are off; the snap button makes the corners snap to
  right angles.
- The rotate buttons turn the page in 90° steps, the aspect ratio button
  fixes a page format.
- For book pages that bend towards the spine, switch on curved edges and
  drag the middle points of the top and bottom edge onto the curved edge
  of the page.
- Tap **Crop** to go on.

## Recognising text

- The OCR screen recognises the text right away. Pick the **Language** of
  the document first if it is not the one shown. Languages are grouped
  by script: German, French, Spanish and most other European languages
  are under *Latin script*.
- **Review** shows the recognised words on the page: tap a word to
  correct it. **Share text** hands the text to another app.
- Tap **Next** to go to the export. Changing the language later shows
  **Process** to recognise the page again.

## Exporting

- Choose **PDF** or **JPEG**. A PDF contains the recognised text, so it
  can be searched and copied.
- **Save** writes the file to a folder you pick, **Share** hands it to
  another app.
- For a document with several pages, scan each page on its own: every
  finished scan is kept, and **Add page** adds completed scans to the
  current document. Drag the pages in the strip to change their order.
- **Adjust crop** goes back to the crop of the current page, **New scan**
  starts the next document.

## Library and collections

- Every completed scan is kept in the library, reachable through the
  library button on the camera and export screens. Open a scan there to
  see its details, share it again or add it to a collection.
- **Collections** group scans, for example by topic or by year.
- How long scans are kept is set in Options → Library. The default
  keeps all of them.

## Options

- **Scan**: skip the OCR step, skip cropping or edge detection, or show
  the detected page outline live in the camera preview.
- **Camera**: exposure compensation, manual focus, and whether the app
  offers the flashlight in low light.
- **OCR**: the recognition mode and post-processing that corrects common
  recognition errors.
- **Export**: page size, quality, colour, the PDF text layer, a separate
  TXT file with the text, and Inbox Mode.
- **App**: Accessibility Mode, hiding scans from screenshots and the
  recent apps overview, the app lock, the app language, this help and
  the privacy policy.

## App lock

With the app lock on, MakeACopy asks for your fingerprint, face or
screen lock when it starts and when it has been in the background for
more than 30 seconds; picking a file or sharing a document in between
does not ask again. It needs a screen lock on the device. Switch it on
in Options → App.

## Inbox Mode

Inbox Mode saves every export to one folder without asking, for example
a folder that Syncthing, Nextcloud or SambaLite keeps in sync, or that
paperless-ngx imports from. Switch it on in Options → Export under
**Enable default export directory** and pick the folder with **Select
folder**. File names are created from the date; an existing file is
never overwritten.

## Accessibility Mode

Accessibility Mode guides the camera with speech and vibration, so that
a page can be scanned without seeing the screen. The volume keys then
work as the shutter. Switch it on in Options → App.

## Tips for good scans

- Put the page on a dark, plain surface, so that its edges stand out.
- Light from the side or above avoids the phone's shadow on the page.
- Hold the phone parallel to the page; the crop step straightens what is
  left.
- For small print, go closer rather than zooming.

## When something goes wrong

If the app misbehaves, **Share logs** in Options → App collects the
app's own log, which you can send along with a description of the
problem. Report problems at https://github.com/egdels/makeacopy/issues.

## Privacy

MakeACopy has no Internet permission: your documents never leave the
phone unless you save or share them yourself. Details are in the privacy
policy, Options → App → Privacy policy.
