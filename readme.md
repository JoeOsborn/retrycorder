Make sure you have ffmpeg installed and are on a Mac.



Run with these, probably in different terminal windows:
```
lein cljsbuild auto atom-dev
lein figwheel frontend-dev
electron .
```

`ctrl-r` to start recording or set a jump-back point; `ctrl-y` to jump back to the last such point; `ctrl-enter` to finish recording.

This works great with an emulator whose "quick save" is set to the `r` key and whose "quick load" is set to `y`. OpenEmu in particular receives those characters at the same time the `ctrl-` combination is pressed, which makes it ideal.