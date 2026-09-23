# Word-box fixtures of the synthetic layout pages

`<name>.words.json` holds the words (text, left/top/right/bottom, confidence) that the Paddle
pipeline produced for `app/src/androidTestPaddle/assets/synth/<name>.png`, dumped by
`SyntheticPageRegressionTest` on the emulator (see its `dumpWords`). `<name>.gt.txt` is the
page's reference, copied from the same asset folder.

`LayoutWordsFixtureTest` runs the column policy and the line grouping on these boxes without
any recogniser, so the layout layer is pinned on real geometry in a JVM test. Regenerate the
dumps after a change to the detector or the recogniser: install the app and the test APK,
run the test with `am instrument`, pull `files/synth-words/` from the app's external files
directory, and re-check the intact floors in the test.
