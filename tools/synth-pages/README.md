# Synthetic test pages for the Paddle OCR pipeline

`gen_issue87_pages.py` renders four pages (see `app/src/androidTestPaddle/assets/synth/README.md`)
with Pillow and the bundled Liberation fonts (SIL Open Font License, see `fonts/LICENSE`).

    python3 gen_issue87_pages.py <output dir>

`reference_ocr.py` runs the reference PaddleOCR pipeline through the paddle-worker engine of the
makeacopy monorepo over the same images and writes `<name>.reference.json` next to them. It
needs that repository's virtualenv and model directory; see the docstring.
