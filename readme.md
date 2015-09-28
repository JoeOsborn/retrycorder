to run protocol tester:

env LEIN_FAST_TRAMPOLINE=y lein trampoline cljsbuild auto core-dev
env LEIN_FAST_TRAMPOLINE=y lein trampoline cljsbuild auto atom-dev
env LEIN_FAST_TRAMPOLINE=y lein trampoline figwheel frontend-dev
electron .