The native `audio_buffer_policy.c` is adapted from
`crates/punktfunk-core/src/audio/jitter.rs` in punktfunk commit
`d016f73683b6b96ca64da99679ca5f4005846726`:

https://git.unom.io/unom/punktfunk/src/commit/d016f73683b6b96ca64da99679ca5f4005846726/crates/punktfunk-core/src/audio/jitter.rs

The MIT option of the upstream MIT OR Apache-2.0 license is used. The original
copyright and permission notice is in LICENSE-MIT and included in the APK at
`assets/licenses/punktfunk-audio.txt`.

The port uses PCM frame counts and a 40 ms initial target with the upstream 25 ms
base, 90 ms adaptive ceiling, and unsynchronised jitter policy. Crossfade weights
are shared by all channels of a PCM frame. A/V sync-driven duplication and native
protocol packet loss recovery are not included.
